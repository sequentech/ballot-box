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
  val allowPartialTallies = Play.current.configuration.getBoolean("app.partial-tallies").getOrElse(false)
  val authorities = getAuthorityData

  /** inserts election into the db in the registered state */
  def register(id: Long) = HAction("", "AuthEvent", id, "edit").async(BodyParsers.parse.json) { request =>
    registerElection(request, id)
  }

  /** updates an election's config */
  def update(id: Long) = HAction("", "AuthEvent", id, "admin").async(BodyParsers.parse.json) { request =>
    updateElection(id, request)
  }

  /** gets an election */
  def get(id: Long) = Action.async { request =>

    val future = getElection(id).map { election =>
      Ok(response(election.getDTO))
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.EO_ERROR))
    }
  }

  /** Creates an election in eo */
  def create(id: Long) = HAction("", "AuthEvent", id, "edit").async { request =>

    getElection(id).flatMap(createElection).recover {

      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.EO_ERROR))

      case t:Throwable => {
        Logger.error("Error creating election", t)
        InternalServerError(error(t.toString, ErrorCodes.EO_ERROR))
      }
    }
  }

  /** sets election in started state, votes will be accepted */
  def start(id: Long) = HAction("", "AuthEvent", id, "edit").async { request => Future {

    val ret = DAL.elections.updateState(id, Elections.STARTED)
    Ok(response(ret))

  }(slickExecutionContext)}

  /** sets election in stopped state, votes will not be accepted */
  def stop(id: Long) = HAction("", "AuthEvent", id, "edit").async { request => Future {

    val ret = DAL.elections.updateState(id, Elections.STOPPED)
    Ok(response(ret))

  }(slickExecutionContext)}

  /** request a tally, dumps votes to the private ds */
  def tally(id: Long) = HAction("", "AuthEvent", id, "edit").async { request =>

    val tally = getElection(id).flatMap { e =>
      if( (e.state == Elections.STOPPED) || allowPartialTallies ) {
        BallotboxApi.dumpTheVotes(e.id).flatMap(_ => tallyElection(e))
      }
      else {
        Logger.warn(s"Cannot tally election $id in wrong state ${e.state}")
        Future { BadRequest(error(s"Cannot tally election $id in wrong state ${e.state}")) }
      }
    }
    tally.recover(tallyErrorHandler)
  }

  /** request a tally, dumps votes to the private ds. Only tallies votes matching passed in voter ids */
  def tallyWithVoterIds(id: Long) = HAction("", "AuthEvent", id, "edit").async(BodyParsers.parse.json) { request =>

    val validIds = request.body.asOpt[List[String]].map(_.toSet)

    val tally = getElection(id).flatMap { e =>
      if( (e.state == Elections.STOPPED) || allowPartialTallies ) {
        BallotboxApi.dumpTheVotes(e.id, validIds).flatMap(_ => tallyElection(e))
      }
      else {
        Logger.warn(s"Cannot tally election $id in wrong state ${e.state}")
        Future { BadRequest(error(s"Cannot tally election $id in wrong state ${e.state}")) }
      }
    }
    tally.recover(tallyErrorHandler)
  }

  /** request a tally, but do not dump votes, use those in the private ds */
  def tallyNoDump(id: Long) = HAction("", "AuthEvent", id, "edit").async { request =>

    val tally = getElection(id).flatMap { e =>
      if( (e.state == Elections.STOPPED) || allowPartialTallies ) {
        tallyElection(e)
      }
      else {
        Logger.warn(s"Cannot tally election $id in wrong state ${e.state}")
        Future { BadRequest(error(s"Cannot tally election $id in wrong state ${e.state}")) }
      }
    }
    tally.recover(tallyErrorHandler)
  }

  /** calculate the results for a tally using agora-results */
  def calculateResults(id: Long) = HAction("", "AuthEvent", id, "edit").async(BodyParsers.parse.json) { request =>

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

  def publishResults(id: Long) = HAction("", "AuthEvent", id, "edit").async {

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

  def getResults(id: Long) = HAction("", "AuthEvent", id, "get-results").async { request =>

    val future = getElection(id).map { election =>
      Ok(response(election.results))
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found"))
    }
  }

  /** dump pks to the public datastore, this is an admin only command */
  def dumpPks(id: Long) = HAction("", "AuthEvent", id, "edit").async { request =>

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

  def getAuthorities = Action.async { request => Future {
      Ok(response(authorities.mapValues(_ \ "public")))
  }}

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

          downloadTally(resp.data.tally_url, id).map { _ =>

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

  /** Future: inserts election into the db in the registered state */
  private def registerElection(request: Request[JsValue], id: Long) = Future {

    val electionConfig = request.body.validate[ElectionConfig]

    electionConfig.fold(

      errors => {
        Logger.warn(s"Invalid config json, $errors")
        BadRequest(error(s"Invalid config json " + JsError.toFlatJson(errors)))
      },

      config => {

        val validated = config.validate(authorities, id)

        DB.withSession { implicit session =>

          val existing = DAL.elections.findByIdWithSession(validated.id)
          existing match {

            case Some(_) => BadRequest(error(s"election with id ${config.id} already exists"))

            case None => {
              val result = DAL.elections.insert(Election(validated.id, validated.asString,
                Elections.REGISTERED, validated.start_date, validated.end_date, None, None, None))
              Ok(response(result))
            }
          }
        }
      }
    )
  }(slickExecutionContext)

  /** Future: updates an election's config */
  private def updateElection(id: Long, request: Request[JsValue]) = Future {

    val electionConfig = request.body.validate[ElectionConfig]

    electionConfig.fold(

      errors => {
        BadRequest(response(JsError.toFlatJson(errors)))
      },

      config => {

        val validated = config.validate(authorities, id)

        val result = DAL.elections.updateConfig(id, validated.asString, validated.start_date, validated.end_date)
        Ok(response(result))
      }
    )

  }(slickExecutionContext)

  /** Future: links tally and copies results into public datastore */
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

    val url = eoUrl(config.director, "public_api/tally")
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
    // make sure that all requested authorities are available
    auths.foreach { auth =>
      if(!authorities.contains(auth)) {
        return Future {
          BadRequest(error("One or more authorities were not found", ErrorCodes.MISSING_AUTH))
        }
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
    val url = eoUrl(config.director, "public_api/election")
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
  private def getAuthorityData: Map[String, JsObject] = {

    val dir = Play.current.configuration.getString("app.eopeers.dir").get
    val peersDir = new java.io.File(dir)

    val peerFiles = peersDir.listFiles.filter(f => !f.isDirectory &&
      (f.getName.endsWith(".pkg")||f.getName.endsWith(".package"))
    )

    val peers = peerFiles.map { file =>
      val text = scala.io.Source.fromFile(file)
      // get the file name without extension
      val ar = file.getName().split('.')
      val key = ar.slice(0, ar.length - 1).mkString(".")
      val allLines = text.mkString
      val peer = Json.parse(allLines).as[JsObject]
      text.close()

      // add extra authority info
      val appcfg = Play.current.configuration
      val authCfg = s"app.authorities.$key"

      val name = appcfg.getString(s"app.authorities.$key.name").getOrElse("Unnamed Authority")
      val _name = "name" -> JsString(name)
      val description = appcfg.getString(s"app.authorities.$key.description").getOrElse("Authority description")
      val _description = "description" -> JsString(description)
      val extra = AuthData(appcfg.getString(s"$authCfg.name"), appcfg.getString(s"$authCfg.description"),
        appcfg.getString(s"$authCfg.url"), appcfg.getString(s"$authCfg.image"))

      // val peerExtra = peer + _name + _description
      val peerExtra = peer + ("public" -> Json.toJson(extra))

      println(peerExtra)
      key -> peerExtra
    }

    peers.toMap
  }

  /** creates the json auth data for the given set of authorities */
  private def getAuthData(auths: Set[String]): JsArray = {

    val data = auths.map { a =>
      Json.obj(
        "name" -> a,
        "orchestra_url" -> eoUrl(a, "api/queues"),
        "ssl_cert" -> authorities(a) \ "ssl_certificate"
      )
    }
    JsArray(data.toSeq)
  }

  /** creates the json data for an eo tally operation */
  private def getTallyData(electionId: Long) = {

    val votesHash = Datastore.hashVotes(electionId)
    Json.obj(
      "election_id" -> electionId,
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
    val port = authorities.get(auth).map(_ \ "port").getOrElse(5000)
    s"https://$auth:$port/$path"
  }

  /** gets our api url (for eo initiated callbacks) */
  private def apiUrl(suffix: String) = {
    s"$urlRoot" + suffix
  }
}