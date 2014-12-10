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

/**
  * Elections api
  *
  * Pending proper threadpool isolation
  * see
  *
  * http://stackoverflow.com/questions/19780545/play-slick-with-securesocial-running-db-io-in-a-separate-thread-pool
  * https://github.com/playframework/play-slick/issues/105
  * implicit val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")
  *
  */
object ElectionsApi extends Controller with Response {

  // we deliberately crash startup if these are not set
  val urlRoot = Play.current.configuration.getString("app.api.root").get
  val peers = getPeers

  /** inserts election into the db in the registered state */
  def register = LHAction("register").async(BodyParsers.parse.json) { request => Future {

    val electionConfig = request.body.validate[ElectionConfig]

    electionConfig.fold(
      errors => BadRequest(error(s"Invalid config json " + JsError.toFlatJson(errors), ErrorCodes.EO_ERROR)),

      config => {
        DB.withSession { implicit session =>
          val result = Elections.insert(Election(config.election_id, request.body.toString,
            Elections.REGISTERED, config.voting_start_date, config.voting_end_date, None))

          Ok(response(result))
        }
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
    LHAction("update-$0", List(id)).async(BodyParsers.parse.json) { request => Future {

    val electionConfig = request.body.validate[ElectionConfig]

    electionConfig.fold(
      errors => {
        BadRequest(response(JsError.toFlatJson(errors)))
      },
      config => {
        DB.withSession { implicit session =>
          // val result = Elections.updateConfig(id, request.body.toString, config.voting_start_date, config.voting_end_date)
          val result = DAL.elections.updateConfig(id, request.body.toString, config.voting_start_date, config.voting_end_date)
          Ok(response(result))
        }
      }
    )
  }}

  /** Creates an election in eo */
  def create(id: Long) = LHAction("create-$0", List(id)).async { request =>
    getElection(id).flatMap(createElection).recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.EO_ERROR))
      case t:Throwable => {
        Logger.error("Error creating election", t)
        InternalServerError(error(t.toString, ErrorCodes.EO_ERROR))
      }
    }
  }

  /** sets election in started state, votes will be accepted */
  def start(id: Long) = LHAction("start-$0", List(id)) { request =>
    val ret = DAL.elections.updateState(id, Elections.STARTED)
    Ok(response(ret))
  }

  /** sets election in stopped state, votes will not be accepted */
  def stop(id: Long) = LHAction("stop-$0", List(id)) { request =>
    DAL.elections.updateState(id, Elections.STOPPED)
    Ok(response("ok"))
  }

  /** request a tally, dumps votes to the private ds */
  def tally(id: Long) = LHAction("tally-$0", List(id)).async { request =>
    val tally = getElection(id).flatMap(e => BallotboxApi.dumpTheVotes(e.id).flatMap(_ => tallyElection(e)))

    tally.recover(tallyErrorHandler)
  }

  /** request a tally, but do not dump votes, use those in the private ds */
  def tallyNoDump(id: Long) = LHAction("tally-$0", List(id)).async { request =>
    val tally = getElection(id).flatMap(tallyElection)

    tally.recover(tallyErrorHandler)
  }

  def calculateResults(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }

  def getResults(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }

  /** dump pks to the public datastore, this is an admin only command */
  def dumpPks(id: Long) = LHAction("admin-$0", List(id)).async { request =>
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

  /** Called by EO when the keys are generated, this saves them and updates state*/
  def keydone(id: Long) = Action.async(BodyParsers.parse.json) { request => Future {
    Logger.info(s"keydone callback ${request.body.toString}")

    val cr = request.body.validate[CreateResponse]
    cr.fold(
        errors => {
          Logger.error(s"Error parsing create response " + JsError.toFlatJson(errors))
          DB.withSession { implicit session =>
            Elections.updateState(id, Elections.CREATE_ERROR)
          }
        },
        response => {
          DB.withSession { implicit session =>
            val pks = response.session_data.map(_.pubkey)
            // Datastore.writeFile(id, "pks", Json.toJson(pks).toString)
            // automatically sets status to CREATED
            Elections.setPublicKeys(id, Json.toJson(pks).toString)
          }
        }
      )
    // we always return the same response to EO
    Ok(Json.toJson(0))
  }}

  /** Called by EO when the tally is completed, this downloads and updates state */
  def tallydone(id: Long) = Action.async(BodyParsers.parse.json) { request =>
    Logger.info(s"tallydone callback ${request.body.toString}")

    val tr = request.body.validate[TallyResponse]
    tr.fold(
      errors => Future {
        Logger.error(s"Error parsing tally response " + JsError.toFlatJson(errors))
        DB.withSession { implicit session =>
          Elections.updateState(id, Elections.TALLY_ERROR)
        }
        Ok(response(0))
      },
      resp => {
        if(resp.status == "finished") {
          downloadTally(resp.data.tally_url, id).map { _ =>
            DB.withSession { implicit session =>
              Elections.updateState(id, Elections.TALLY_OK)
            }
            Ok(response(0))
          }
        } else {
          Future {
            Logger.error(s"EO returned error on tally, ${resp.toString}")
            DB.withSession { implicit session =>
              Elections.updateState(id, Elections.TALLY_ERROR)
            }
            Ok(response(0))
          }
        }
      }
    )
  }

  /*-------------------------------- privates  --------------------------------*/

  /** Future: downloads a tally from eo */
  private def downloadTally(url: String, electionId: Long) = {
    import play.api.libs.iteratee._
    import java.nio.file._

    Logger.info(s"downloading tally from $url")

    // taken from https://www.playframework.com/documentation/2.3.x/ScalaWS

    // make the request
    val futureResponse: Future[(WSResponseHeaders, Enumerator[Array[Byte]])] =
    WS.url(url).getStream()

    val downloadedFile: Future[Unit] = futureResponse.flatMap {
      case (headers, body) =>
       val out = Datastore.getTallyStream(electionId)

      // The iteratee that writes to the output stream
      val iteratee = Iteratee.foreach[Array[Byte]] { bytes =>
        out.write(bytes)
      }

      // Feed the body into the iteratee
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
    Logger.info("requesting tally with\n" + data)

    val url = eoUrl(config.director, "public_api/tally")
    WS.url(url).post(data).map { resp =>
      if(resp.status == HTTP.ACCEPTED) {
        Ok(response(0))
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
    // FIXME once EO accepts an integer id remove this part
    val data = withAuthorities - "election_id" + ("election_id" -> JsString(election.id.toString))

    Logger.info("creating election with\n" + data)

    // create election in eo
    val url = eoUrl(config.director, "public_api/election")
    WS.url(url).post(data).map { resp =>
      if(resp.status == HTTP.ACCEPTED) {
        Ok(response(0))
      }
      else {
        Logger.error(s"EO returned status ${resp.status} with body ${resp.body}")
        BadRequest(error(s"EO returned status ${resp.status}", ErrorCodes.EO_ERROR))
      }
    }
  }

  /** Future: returns an election given its id, may throw nosuchelement exception */
  private def getElection(id: Long): Future[Election] = Future {
    DB.withSession { implicit session =>
      Elections.findById(id).get
    }
  }

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

  /** gets the api url for eo authority */
  private def eoUrl(auth: String, path: String) = {
    val port = peers.get(auth).map(_ \ "port").getOrElse(5000)
    s"https://$auth:$port/$path"
  }

  /** gets our api url (for eo initiated callbacks) */
  private def apiUrl(suffix: String) = {
    s"$urlRoot" + suffix
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
      "votes_hash" -> s"sha512://$votesHash"
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
}