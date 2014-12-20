package controllers

import models._
import utils._
import utils.JsonFormatters._
import utils.Response

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.Play.current
import play.api.db.slick.DB
import play.libs.Akka
import play.api.http.{Status => HTTP}
import play.api.libs.ws._

import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent._
import scala.sys.process._

/**
  * Elections api
  *
  * General election management. An election's lifecyle is
  *
  * registered -> created -> started -> stopped -> tally_ok -> results_ok
  *
  * Threadpool isolation is implemented via futures, see
  *
  * http://stackoverflow.com/questions/19780545/play-slick-with-securesocial-running-db-io-in-a-separate-thread-pool
  * https://github.com/playframework/play-slick/issues/105
  * implicit val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")
  *
  */
object ElectionsApi extends Controller with Response {

  // we deliberately crash startup if these are not set
  val urlRoot = Play.current.configuration.getString("app.api.root").get
  val agoraResults = Play.current.configuration.getString("app.results.script").getOrElse("./admin/results.sh")
  val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")
  val peers = getPeers

  /** inserts election into the db in the registered state */
  def register = HAction("register").async(BodyParsers.parse.json) { request => Future {

    val electionConfig = request.body.validate[ElectionConfig]

    electionConfig.fold(

      errors => {
        Logger.warn(s"Invalid config json, $errors")
        BadRequest(error(s"Invalid config json " + JsError.toFlatJson(errors)))
      },

      config => {

        val result = DAL.elections.insert(Election(config.id, request.body.toString,
            Elections.REGISTERED, config.start_date, config.end_date, None, None, None))

        Ok(response(result))
      }
    )
  }}

  /** gets an election */
  def get(id: Long) = LoggingAction.async { request =>

    val future = getElection(id).map { election =>
      Ok(response(election))
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.EO_ERROR))
    }
  }

  /** Updates an election's config */
  def update(id: Long) =
    HAction("update-$0", List(id)).async(BodyParsers.parse.json) { request => Future {

    val electionConfig = request.body.validate[ElectionConfig]

    electionConfig.fold(

      errors => {
        BadRequest(response(JsError.toFlatJson(errors)))
      },

      config => {
        val result = DAL.elections.updateConfig(id, request.body.toString, config.start_date, config.end_date)
        Ok(response(result))
      }
    )

  }(slickExecutionContext)}

  /** Creates an election in eo */
  def create(id: Long) = HAction("create-$0", List(id)).async { request =>

    getElection(id).flatMap(createElection).recover {

      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.EO_ERROR))

      case t:Throwable => {
        Logger.error("Error creating election", t)
        InternalServerError(error(t.toString, ErrorCodes.EO_ERROR))
      }
    }
  }

  /** sets election in started state, votes will be accepted */
  def start(id: Long) = HAction("start-$0", List(id)).async { request => Future {

    val ret = DAL.elections.updateState(id, Elections.STARTED)
    Ok(response(ret))

  }(slickExecutionContext)}

  /** sets election in stopped state, votes will not be accepted */
  def stop(id: Long) = HAction("stop-$0", List(id)).async { request => Future {

    val ret = DAL.elections.updateState(id, Elections.STOPPED)
    Ok(response(ret))

  }(slickExecutionContext)}

  /** request a tally, dumps votes to the private ds */
  def tally(id: Long) = HAction("tally-$0", List(id)).async { request =>

    val tally = getElection(id).flatMap(e => BallotboxApi.dumpTheVotes(e.id).flatMap(_ => tallyElection(e)))
    tally.recover(tallyErrorHandler)
  }

  /** request a tally, but do not dump votes, use those in the private ds */
  def tallyNoDump(id: Long) = HAction("tally-$0", List(id)).async { request =>

    val tally = getElection(id).flatMap(tallyElection)
    tally.recover(tallyErrorHandler)
  }

  /** calculate the results for a tally using agora-results */
  def calculateResults(id: Long) = HAction("results-$0", List(id)).async(BodyParsers.parse.json) { request =>

    val config = request.body.toString
    Logger.info(s"calculating results for election $id")

    val future = getElection(id).flatMap { e =>

      if(e.state == Elections.TALLY_OK || e.state == Elections.RESULTS_OK) {
        calcResults(id, config).flatMap( r => updateResults(e, r) )
      } else {
        Logger.warn(s"Cannot calculate results for election $id in wrong state ${e.state}")
        Future { BadRequest(error(s"Cannot calculate results for election $id in wrong state ${e.state}")) }
      }
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found"))
    }
  }

  def publishResults(id: Long) = HAction("admin-$0", List(id)).async {

    Logger.info(s"publishing results for election $id")

    val future = getElection(id).flatMap { e =>

      if(e.state == Elections.TALLY_OK || e.state == Elections.RESULTS_OK) {
        pubResults(id, e.results)
      }
      else {
        Logger.warn(s"cannot calculate results for election $id in unexpected state ${e.state}")
        Future { BadRequest(error(s"cannot calculate results for election $id in unexpected state ${e.state}")) }
      }
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found"))
      case i:IllegalStateException => BadRequest(error(s"Election had no results"))
      case f:java.io.FileNotFoundException => BadRequest(error(s"Election had no tally"))
    }
  }

  def getResults(id: Long) = HAction("results-$0", List(id)).async { request =>

    val future = getElection(id).map { election =>
      Ok(response(election.results))
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found"))
    }
  }

  /** dump pks to the public datastore, this is an admin only command */
  def dumpPks(id: Long) = HAction("admin-$0", List(id)).async { request =>

    val future = getElection(id).map { election =>
      val mapped = election.pks.map { pks =>

        Datastore.dumpPks(id, pks)
        Ok(response("ok"))
      }
      mapped.getOrElse(BadRequest(error(s"No PKS for election $id", ErrorCodes.NO_PKS)))
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.NO_ELECTION))
    }
  }

  /*-------------------------------- EO Callbacks  --------------------------------*/

  /** Called by EO when the keys are generated, this saves them and updates state */
  def keydone(id: Long) = Action.async(BodyParsers.parse.json) { request => Future {

    Logger.info(s"keydone callback ${request.body.toString}")

    val cr = request.body.validate[CreateResponse]
    cr.fold(

        errors => {
          Logger.error(s"Error parsing create response " + JsError.toFlatJson(errors))
          DAL.elections.updateState(id, Elections.CREATE_ERROR)
        },

        response => {
          val pks = response.session_data.map(_.pubkey)
          // automatically sets status to CREATED
          DAL.elections.setPublicKeys(id, Json.toJson(pks).toString)
        }
      )
    // we always return the same response to EO
    Ok(Json.toJson(0))

  }(slickExecutionContext)}

  /** Called by EO when the tally is completed, this downloads and updates state */
  def tallydone(id: Long) = Action.async(BodyParsers.parse.json) { request =>

    Logger.info(s"tallydone callback ${request.body.toString}")

    val tr = request.body.validate[TallyResponse]
    tr.fold(

      errors => Future {
        Logger.error(s"Error parsing tally response " + JsError.toFlatJson(errors))

        DAL.elections.updateState(id, Elections.TALLY_ERROR)
        Ok(response(0))

      }(slickExecutionContext),

      resp => {
        if(resp.status == "finished") {
          // FIXME hardcoded port
          downloadTally(resp.data.tally_url.replace("5000", "11000"), id).map { _ =>

            DAL.elections.updateState(id, Elections.TALLY_OK)
            Ok(response(0))
          }
        } else {
          Future {
            Logger.error(s"EO returned error on tally, ${resp.toString}")

            DAL.elections.updateState(id, Elections.TALLY_ERROR)
            Ok(response(0))

          }(slickExecutionContext)
        }
      }
    )
  }

  /*-------------------------------- privates  --------------------------------*/

  private def pubResults(id: Long, results: Option[String]) = Future {

    Datastore.publishResults(id, results)
    Ok(response("ok"))

  }(slickExecutionContext)

  /** Future: calculates an election's results using agora-results */
  private def calcResults(id: Long, config: String) = Future {

    val configPath = Datastore.writeResultsConfig(id, config)
    val tallyPath = Datastore.getTallyPath(id)
    val cmd = s"$agoraResults -t $tallyPath -c $configPath -s"

    Logger.info(s"executing '$cmd'")
    val output = cmd.!!
    Logger.info(s"command returns\n$output")

    output
  }

  /** Future: updates an election's results */
  private def updateResults(election: Election, results: String) = Future {

    DAL.elections.updateResults(election.id, results)
    Ok(Json.toJson("ok"))

  }(slickExecutionContext)

  /** Future: downloads a tally from eo */
  private def downloadTally(url: String, electionId: Long) = {
    import play.api.libs.iteratee._

    Logger.info(s"downloading tally from $url")

    // taken from https://www.playframework.com/documentation/2.3.x/ScalaWS
    val futureResponse: Future[(WSResponseHeaders, Enumerator[Array[Byte]])] =
    WS.url(url).getStream()

    val downloadedFile: Future[Unit] = futureResponse.flatMap {
      case (headers, body) =>
       val out = Datastore.getTallyStream(electionId)

      val iteratee = Iteratee.foreach[Array[Byte]] { bytes =>
        out.write(bytes)
      }

      (body |>>> iteratee).andThen {
        case result =>
        // Close the output stream whether there was an error or not
        out.close()
        // Get the result or rethrow the error
        result.get
      }
    }

    downloadedFile
  }

  /** Future: tallies an election in eo */
  private def tallyElection(election: Election): Future[Result] = {

    val configJson = Json.parse(election.configuration)
    val config = configJson.validate[ElectionConfig].get

    // get the tally data, including votes hash, url and callback
    val data = getTallyData(election.id)
    Logger.info(s"requesting tally with\n$data")

    // FIXME remove hardcoded port replace
    val url = eoUrl(config.director, "public_api/tally").replace("5000", "11000")
    WS.url(url).post(data).map { resp =>

      if(resp.status == HTTP.ACCEPTED) {
        Ok(response("ok"))
      }
      else {
        BadRequest(error(s"EO returned status ${resp.status} with body ${resp.body}", ErrorCodes.EO_ERROR))
      }
    }
  }

  /** Future: creates an election in eo */
  private def createElection(election: Election): Future[Result] = {

    val configJson = Json.parse(election.configuration)
    val config = configJson.validate[ElectionConfig].get
    // collect all authorities
    val auths = (config.director +: config.authorities).toSet
    // make sure that all requested authorities are available as peers
    val present = auths.map(peers.contains _)
    if(present.contains(false)) {
      return Future {
        BadRequest(error("One or more authorities were not found (eopeers dir)", ErrorCodes.MISSING_AUTH))
      }
    }

    // construct the auth data json field
    val authData = getAuthData(auths)
    // add the callback and auth data fields to the original config
    val jsObject = configJson.as[JsObject]
    val callback = "callback_url" -> JsString(apiUrl(routes.ElectionsApi.keydone(election.id).url))
    Logger.info("create callback is " + callback)

    val withCallback = (jsObject + callback)
    val withAuthorities = withCallback - "authorities" + ("authorities" -> authData)

    Logger.info(s"creating election with\n$withAuthorities")

    // create election in eo
    // FIXME remove hardcoded port replace
    val url = eoUrl(config.director, "public_api/election").replace("5000", "11000")
    Logger.info(s"requesting at $url")
    WS.url(url).post(withAuthorities).map { resp =>

      if(resp.status == HTTP.ACCEPTED) {
        Ok(response("ok"))
      }
      else {
        Logger.error(s"EO returned status ${resp.status} with body ${resp.body}")
        BadRequest(error(s"EO returned status ${resp.status}", ErrorCodes.EO_ERROR))
      }
    }
  }

  /** Future: returns an election given its id, may throw nosuchelement exception */
  private def getElection(id: Long): Future[Election] = Future {

    DAL.elections.findById(id).get

  }(slickExecutionContext)

  /** creates a Map[peer name => peer json] based on eopeer installed packages */
  private def getPeers: Map[String, JsObject] = {

    val dir = Play.current.configuration.getString("app.eopeers.dir").get
    val peersDir = new java.io.File(dir)

    val peers = peersDir.listFiles.filter(!_.isDirectory).map { file =>
      val text = scala.io.Source.fromFile(file)
      // get the file name without extension
      val name = file.getName().split('.')(0)
      val allLines = text.mkString
      val peer = Json.parse(allLines).as[JsObject]
      text.close()
      name -> peer
    }

    peers.toMap
  }

  /** creates the json auth data for the given set of authorities */
  private def getAuthData(authorities: Set[String]): JsArray = {

    val auths = authorities.map { a =>
      Json.obj(
        "name" -> a,
        "orchestra_url" -> eoUrl(a, "api/queues"),
        "ssl_cert" -> peers(a) \ "ssl_certificate"
      )
    }
    JsArray(auths.toSeq)
  }

  /** creates the json data for an eo tally operation */
  private def getTallyData(electionId: Long) = {

    val votesHash = Datastore.hashVotes(electionId)
    Json.obj(
      "election_id" -> electionId.toString,
      "callback_url" -> apiUrl(routes.ElectionsApi.tallydone(electionId).url),
      "extra" -> List[String](),
      "votes_url" -> Datastore.getCiphertextsUrl(electionId),
      "votes_hash" -> s"ni:///sha-256;$votesHash"
    )
  }

  /** handles errors for tally futures */
  private val tallyErrorHandler: PartialFunction[Throwable, Result] = {

    case e:NoSuchElementException => BadRequest(error(s"Election not found", ErrorCodes.NO_ELECTION))

    case t:Throwable => {
      Logger.error("Error tallying election", t)
      InternalServerError(error("Error while launching tally", ErrorCodes.TALLY_ERROR))
    }
  }

  /** gets the api url for eo authority */
  private def eoUrl(auth: String, path: String) = {
    val port = peers.get(auth).map(_ \ "port").getOrElse(5000)
    s"https://$auth:$port/$path"
  }

  /** gets our api url (for eo initiated callbacks) */
  private def apiUrl(suffix: String) = {
    s"$urlRoot" + suffix
  }
}