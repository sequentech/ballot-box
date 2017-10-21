/**
 * This file is part of agora_elections.
 * Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

 * agora_elections is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * agora_elections  is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with agora_elections.  If not, see <http://www.gnu.org/licenses/>.
**/
package controllers

import models._
import utils._
import utils.JsonFormatters._
import utils.Response
import java.util.Date

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

import java.nio.file.{Paths, Files}

/**
  * Elections api
  *
  * General election management. An election's lifecyle is
  *
  * registered -> created -> started -> stopped -> doing_tally -> tally_ok -> results_ok -> results_pub
  *
  * Threadpool isolation is implemented via futures, see
  *
  * http://stackoverflow.com/questions/19780545/play-slick-with-securesocial-running-db-io-in-a-separate-thread-pool
  * https://github.com/playframework/play-slick/issues/105
  * implicit val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")
  *
  */

trait ErrorProcessing {
  /**
   * Get the message safely from a `Throwable`
   */
  def getMessageFromThrowable(t: Throwable): String = {
    if (null == t.getCause) {
        t.toString
     } else {
        t.getCause.getMessage
     }
  }
}

object ElectionsApi 
  extends Controller
  with Response
  with ErrorProcessing {

  // we deliberately crash startup if these are not set
  val urlRoot = Play.current.configuration.getString("app.api.root").get
  val urlSslRoot = Play.current.configuration.getString("app.datastore.ssl_root").get
  val agoraResults = Play.current.configuration.getString("app.results.script").getOrElse("./admin/results.sh")
  val pipesWhitelist = Play.current.configuration.getString("app.agoraResults.pipesWhitelist").getOrElse("")
  val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")
  val allowPartialTallies = Play.current.configuration.getBoolean("app.partial-tallies").getOrElse(false)
  val authorities = getAuthorityData
  val download_tally_timeout = Play.current.configuration.getInt("app.download_tally_timeout").get
  val download_tally_retries = Play.current.configuration.getInt("app.download_tally_retries").get
  val always_publish = Play.current.configuration.getBoolean("app.always_publish").getOrElse(false)
  val startedCallbackUrl = Play.current.configuration.getString("app.callbacks.started").
    flatMap { started =>
      if (started.length > 0) {
        Some(started)
      } else {
        None
      }
    }
  val publishedCallbackUrl = Play.current.configuration.getString("app.callbacks.published").
    flatMap { published =>
      if (published.length > 0) {
        Some(published)
      } else {
        None
      }
    }
  val boothSecret = Play.current.configuration.getString("booth.auth.secret").get

  /** inserts election into the db in the registered state */
  def register(id: Long) = HAction("", "AuthEvent", id, "edit|register").async(BodyParsers.parse.json) { request =>
    registerElection(request, id)
  }

  /** updates an election's config */
  def update(id: Long) = HAction("", "AuthEvent", id, "edit|update").async(BodyParsers.parse.json) { request =>
    updateElection(id, request)
  }  

  /** updates an election's social share buttons config */
  def updateShare(id: Long) = HAction("", "AuthEvent", id, "edit|update-share").async(BodyParsers.parse.json) { request =>
    updateShareElection(id, request)
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
  def create(id: Long) = HAction("", "AuthEvent", id, "edit|create").async { request =>

    getElection(id).flatMap(createElection).recover {

      case e:NoSuchElementException => BadRequest(error(s"Election $id not found", ErrorCodes.EO_ERROR))

      case t:Throwable => {
        Logger.error("Error creating election", t)
        InternalServerError(error(t.toString, ErrorCodes.EO_ERROR))
      }
    }
  }

  /** sets election in started state, votes will be accepted */
  def start(id: Long) = HAction("", "AuthEvent", id, "edit|start").async { request => Future {

    val ret = DAL.elections.updateState(id, Elections.STARTED)
    Future {
      startedCallbackUrl map { callback_url =>
        postCallback(id, callback_url, Callback(Elections.STARTED, ""))
      }
    }
    Ok(response(ret))

  }(slickExecutionContext)}

  /** sets election in stopped state, votes will not be accepted */
  def stop(id: Long) = HAction("", "AuthEvent", id, "edit|stop").async { request => Future {

    val ret = DAL.elections.updateState(id, Elections.STOPPED)
    Ok(response(ret))

  }(slickExecutionContext)}

  /** request a tally, dumps votes to the private ds */
  def tally(id: Long) = HAction("", "AuthEvent", id, "edit|tally").async { request =>

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
  def tallyWithVoterIds(id: Long) = HAction("", "AuthEvent", id, "edit|tally").async(BodyParsers.parse.json) { request =>

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
  def tallyNoDump(id: Long) = HAction("", "AuthEvent", id, "edit|tally").async { request =>

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
  def calculateResults(id: Long) = HAction("", "AuthEvent", id, "edit|calculate-results")
    .async(BodyParsers.parse.json)
    {
      request =>
        Logger.info(s"calculating results for election $id")

        val future = getElection(id).flatMap
        {
          e =>
            // if no config is provided and one is available in the election
            // use that one
            val config =
              if (e.resultsConfig.isDefined)
                e.resultsConfig.get
              else
                request.body.toString

            var electionConfigStr = Json.parse(e.configuration).as[JsObject]
            if (!electionConfigStr.as[JsObject].keys.contains("virtualSubelections"))
            {
                electionConfigStr = electionConfigStr.as[JsObject] + ("virtualSubelections" -> JsArray())
            }
            val electionConfig = electionConfigStr.validate[ElectionConfig]

            electionConfig.fold(
              errors =>
              {
                Logger.warn(s"Invalid config json, $errors")
                Future {
                  BadRequest(
                    error(s"Invalid config json " + JsError.toFlatJson(errors))
                  )
                }
              },
              configJson =>
              {
                try
                {
                  val validated = configJson.validate(authorities, id)
                  DB.withSession
                  {
                    implicit session =>
                      // check that related subelections exist and have a tally
                      val notTalliedSubelections = validated.virtualSubelections.get.filter(
                        (eid) =>
                        {
                          val el = DAL.elections.findByIdWithSession(eid)

                          !el.isDefined ||
                          (
                            el.get.state != Elections.TALLY_OK &&
                            el.get.state != Elections.RESULTS_OK &&
                            el.get.state != Elections.RESULTS_PUB
                          )

                        }
                      )
                      notTalliedSubelections match
                      {
                        case l if l.length > 0 =>
                          Future {
                            BadRequest(
                              error(
                                s"election depends on some virtualSubelections that " +
                                s"do not exist. The list of not tallied elections " +
                                s"is: ${notTalliedSubelections}."
                              )
                            )
                          }
                        case _ =>
                          if(
                            (Elections.TALLY_OK == e.state || Elections.RESULTS_OK == e.state) ||
                            (e.virtual && e.state != Elections.RESULTS_PUB)
                          ) {
                            calcResults(id, config, validated.virtualSubelections.get).flatMap( r => updateResults(e, r) )
                          }
                          else
                          {
                            Logger.warn(
                              s"Cannot calculate results for election $id in wrong state " +
                              s"${e.state}")

                            Future {
                              BadRequest(
                                error(
                                  s"Cannot calculate results for election $id in wrong " +
                                  s"state ${e.state}"))
                            }
                          }
                      }
                  }
                }
                catch {
                  case e: ValidationException => Future {
                    BadRequest(error(e.getMessage))
                  }
                }
              }
            )
        }

        if  (always_publish) {
          future map { done =>
            publishResultsLogic(id)
          }
        }

        future.recover {
          case e:NoSuchElementException =>
            BadRequest(error(s"Election $id not found"))
        }
    }

  private def publishResultsLogic(id: Long) = {

    Logger.info(s"publishing results for election $id")

    val future = getElection(id).flatMap
    {
      e =>
        if(Elections.RESULTS_OK  == e.state)
        {
          var electionConfigStr = Json.parse(e.configuration).as[JsObject]
          if (!electionConfigStr.as[JsObject].keys.contains("virtualSubelections"))
          {
              electionConfigStr = electionConfigStr.as[JsObject] + ("virtualSubelections" -> JsArray())
          }
          val electionConfig = electionConfigStr.validate[ElectionConfig]

          electionConfig.fold(
            errors =>
            {
              Logger.warn(s"Invalid config json, $errors")
              Future {
                BadRequest(error(s"Invalid config json " + JsError.toFlatJson(errors)))
              }
            },
            config =>
            {
              try
              {
                val validated = config.validate(authorities, id)
                pubResults(id, e.results, validated.virtualSubelections.get)
              }
              catch
              {
                case e: ValidationException => Future {
                  BadRequest(error(e.getMessage))
                }
              }
            }
          )
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

  def publishResults(id: Long) = HAction("", "AuthEvent", id, "edit|publish-results").async {
    publishResultsLogic(id)
  }

  def getResults(id: Long) = HAction("", "AuthEvent", id, "edit|view-results").async { request =>

    val future = getElection(id).map { election =>
      Ok(response(election.results))
    }
    future.recover {
      case e:NoSuchElementException => BadRequest(error(s"Election $id not found"))
    }
  }

  def getElectionVoters(id: Long) = HAction("", "AuthEvent", id, "edit|view-voters").async { request =>
    getVoters(id).map { voters =>
        Ok(response(Json.toJson( voters.map(v => v.voter_id) )))
    }
  }

  def getElectionStats(id: Long) = HAction("", "AuthEvent", id, "edit|view-stats").async { request =>
    getStats(id).map { s =>
        Ok(response(Json.toJson( s )))
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
  private def registerElection(request: Request[JsValue], id: Long) =
  Future {

    var body = request.body.as[JsObject]

    if (!body.as[JsObject].keys.contains("real")) {
        body = body.as[JsObject] + ("real" -> Json.toJson(false))
    }

    if (!body.as[JsObject].keys.contains("virtual")) {
        body = body.as[JsObject] + ("virtual" -> Json.toJson(false))
    }

    if (!body.as[JsObject].keys.contains("virtualSubelections")) {
        body = body.as[JsObject] + ("virtualSubelections" -> JsArray())
    }

    if (!body.as[JsObject].keys.contains("extra_data")) {
        body = body.as[JsObject] + ("extra_data" -> Json.toJson("{}"))
    }

    if (!body.as[JsObject].keys.contains("logo_url")) {
        body = body.as[JsObject] + ("logo_url" -> Json.toJson(""))
    }

    val electionConfig = body.validate[ElectionConfig]

    electionConfig.fold(
      errors =>
      {
        Logger.warn(s"Invalid config json, $errors")
        BadRequest(error(s"Invalid config json " + JsError.toFlatJson(errors)))
      },
      config =>
      {
        try {
          val validated = config.validate(authorities, id).copy(start_date=None, end_date=None)
          DB.withSession
          {
            implicit session =>
              // check that related subelections exist
              val notExistingSubelections = validated.virtualSubelections.get.filter(
                (eid) =>
                  !DAL.elections.findByIdWithSession(eid).isDefined
              )
              notExistingSubelections match
              {
                case l if l.length > 0 =>
                  BadRequest(
                    error(
                      s"election depends on some virtualSubelections that " +
                      s"do not exist. The list of not existing elections " +
                      s"is: ${notExistingSubelections}."
                    )
                  )
                case _ =>
                  val existing = DAL.elections.findByIdWithSession(validated.id)
                  existing match
                  {
                    case Some(_) =>
                      BadRequest(
                        error(s"election with id ${config.id} already exists"))

                    case None =>
                    {
                      val result = DAL.elections.insert(
                        Election(
                          validated.id,
                          validated.asString,
                          Elections.REGISTERED,
                          validated.start_date,
                          validated.end_date,
                          None,
                          validated.resultsConfig,
                          None,
                          None,
                          validated.real,
                          validated.virtual,
                          validated.logo_url
                        )
                      )
                      Ok(response(result))
                    }
                  }
              }
          }
        } catch {
          case e: ValidationException => BadRequest(error(e.getMessage))
        }
      }
    )
  }(slickExecutionContext)

  /** Future: updates an election's share buttons config */
  private def updateShareElection(id: Long, request: Request[JsValue]) : Future[Result] = 
  {
    val promise = Promise[Result]
    Future {
      val allow_edit: Boolean = Play.current.configuration.getBoolean("share_social.allow_edit").getOrElse(false)
      if(allow_edit) {
        var shareText = request.body.validate[Option[Array[ShareTextItem]]]

        shareText match {
          case e: JsError =>
            promise.success(BadRequest(response(JsError.toFlatJson(e))))
          case jST: JsSuccess[Option[Array[ShareTextItem]]] =>
            val future = getElection(id) map { election =>
              val oldConfig = election.getDTO.configuration
              val config = oldConfig.copy(presentation = oldConfig.presentation.copy(share_text = jST.get))
              val validated = config.validate(authorities, id)
              val result = DAL.elections.updateConfig(id, validated.asString, validated.start_date, validated.end_date)
              Ok(response(result))
            } recover { case err =>
              BadRequest(response(getMessageFromThrowable(err)))
            }
            promise.completeWith(future)
        }
      } else {
        promise.success(BadRequest(response("Access Denied: Social share configuration modifications are not allowed")))
      }
    } (slickExecutionContext) recover { case err =>
      promise.success(BadRequest(response(getMessageFromThrowable(err))))
    }
    promise.future
  }

  /** Future: updates an election's config */
  private def updateElection(id: Long, request: Request[JsValue]) = Future {

    var body = request.body.as[JsObject]
    if (!body.as[JsObject].keys.contains("real")) {
        body = body.as[JsObject] + ("real" -> Json.toJson(false))
    }

    if (!body.as[JsObject].keys.contains("extra_data")) {
        body = body.as[JsObject] + ("extra_data" -> Json.toJson("{}"))
    }

    val electionConfig = body.validate[ElectionConfig]

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
  
  private def postCallback(electionId: Long, url1: String, message: Callback) = {
    try {
      val url = url1.replace("${eid}", electionId+"")
      println(s"posting to $url")
      val userId: String = "admin"
      val now: Long = System.currentTimeMillis / 1000
      val timedAuth = s"$userId:AuthEvent:$electionId:Callback:$now"
      val hmac = Crypto.hmac(boothSecret, timedAuth)
      val khmac = s"khmac:///sha-256;$hmac/$timedAuth"
      val f = WS.url(url)
        .withHeaders(
          "Accept" -> "application/json",
          "Authorization" -> khmac)
        .post(Json.toJson(message))
        .map { resp =>
          if(resp.status != HTTP.ACCEPTED) {
            Logger.warn(s"callback url returned status ${resp.status} with body ${resp.body} and khmac ${khmac}")
          }
        }
      f.recover {
        case t: Throwable => {
          Logger.warn(s"Exception caught when posting to callback $t")
        }
      }
    }
    catch {
      case t:Throwable => {
        Logger.warn(s"Exception caught when posting to callback $t")
      }
    }
  }

  /** Future: links tally and copies results into public datastore */
  private def pubResults(
    id: Long,
    results: Option[String], subtallies: Array[Long]) = Future
  {
    Datastore.publishResults(id, results, subtallies)
    DAL.elections.updateState(id, Elections.RESULTS_PUB)
    Future {
      publishedCallbackUrl map { callback_url =>
        postCallback(id, callback_url, Callback(Elections.RESULTS_PUB,""))
      }
    }
    Ok(response("ok"))

  }(slickExecutionContext)

  /** Future: calculates an election's results using agora-results */
  private def calcResults(
    id: Long,
    config: String,
    subelections: Array[Long]
  ) = Future
  {
    // remove previous public results directory, before the execution
    val oldResultsDirsRX = Paths.get(
      Datastore.getDirPath(id, /*isPublic?*/true).toString,
      Datastore.RESULTS_DIR_PREFIX + ".*"
    ).toString.r
    val electionPublicPath = new java.io.File(
      Datastore.getDirPath(id, /*isPublic?*/true).toString
    )

    if (electionPublicPath.isDirectory())
    {
      electionPublicPath.listFiles
        .filter(
          file => {
            oldResultsDirsRX.findFirstIn(file.getAbsolutePath).isDefined &&
            Files.isSymbolicLink(file.toPath)
          }
        )
        .map(
          file => {
            // remove
            file.delete
          }
        )
    }

    val configPath = Datastore.writeResultsConfig(id, config)
    // if there is a list of subelections, instead of the path to the tally of
    // this election, we use the path to the tallies of the subelections
    val tallyPath = subelections match
    {
      case subelList if (subelList.length == 0) =>
        Datastore.getTallyPath(id)
      case _ =>
        subelections.map(
          (subElectionId) =>
            Datastore.getTallyPath(subElectionId)
        ).mkString(" ")
    }
    val dirPath = Datastore.getDirPath(id)
    val cmd = if (pipesWhitelist.length > 0)
        s"$agoraResults -t $tallyPath -c $configPath -s -x $dirPath -eid $id -p $pipesWhitelist"
      else
        s"$agoraResults -t $tallyPath -c $configPath -s -x $dirPath -eid $id"

    Logger.info(s"executing '$cmd'")
    val output = cmd.!!
    Logger.info(s"command returns\n$output")

    // create the public symbolic link to the new results dir
    var newResultsDirRX = Paths.get(
      Datastore.getDirPath(id, /*isPublic?*/false).toString,
      Datastore.RESULTS_DIR_PREFIX + ".*"
    ).toString.r

    var resultsDirs = new java.io.File(
      Datastore.getDirPath(id, /*isPublic?*/false).toString
    ).listFiles
      .filter(
        file => {
          newResultsDirRX.findFirstIn(file.getAbsolutePath).isDefined &&
          file.isDirectory
        }
      )

    if (resultsDirs.length == 1) {
      Files.createSymbolicLink(
        Datastore
          .getPath(id, resultsDirs(0).getName, /*isPublic?*/true),
        resultsDirs(0).toPath
      )
    }

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

    // function to retry the http request a number of times
    def retryWrapper(
      wsRequest: WSRequestHolder,
      f: Future[(WSResponseHeaders, Enumerator[Array[Byte]])],
      times: Int)
    :
      Future[(WSResponseHeaders, Enumerator[Array[Byte]])] =
    {
      f.recoverWith {
        case t : Throwable =>
          val promise = Promise[(WSResponseHeaders, Enumerator[Array[Byte]])]()
          if (times > 0)
          {
            promise completeWith retryWrapper(wsRequest, wsRequest.getStream(), times - 1)
          }
          else
          {
            promise failure t
          }
          promise.future
      }(slickExecutionContext)
    }

    // taken from https://www.playframework.com/documentation/2.3.x/ScalaWS
    // configure http request
    val wsRequest = WS.url(url).withRequestTimeout(download_tally_timeout)
    // http request future (including retries)
    val futureResponse: Future[(WSResponseHeaders, Enumerator[Array[Byte]])] =
      retryWrapper(wsRequest, wsRequest.getStream(), download_tally_retries)

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

    // if there's no votes we don't try the tally
    val votes = DAL.votes.countForElection(election.id)
    if(votes == 0) {
        Future { BadRequest(response("There's no votes in this election")) }
    } else if (election.state == Elections.TALLY_OK || election.state == Elections.DOING_TALLY) {
        Future { Ok(response("ok")) }
    } else {
      // get the tally data, including votes hash, url and callback
      val data = getTallyData(election.id)
      Logger.info(s"requesting tally with\n$data")

      val url = eoUrl(config.director, "public_api/tally")
      WS.url(url).post(data).map { resp =>

        if(resp.status == HTTP.ACCEPTED) {
          DAL.elections.updateState(election.id, Elections.DOING_TALLY)
          Ok(response("ok"))
        }
        else {
          BadRequest(error(s"EO returned status ${resp.status} with body ${resp.body}", ErrorCodes.EO_ERROR))
        }
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
    val callback = "callback_url" -> JsString(apiSslUrl(routes.ElectionsApi.keydone(election.id).url))
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

  /** Future: returns the list of voters from a election id */
  private def getVoters(id: Long): Future[List[Vote]] = Future {
    DAL.votes.findByElectionId(id)
  }(slickExecutionContext)

  private def getStats(id: Long): Future[Stats] = Future {
    val total = DAL.votes.countForElection(id)
    val count = DAL.votes.countUniqueForElection(id)
    val byday = DAL.votes.byDay(id)
    Stats(total, count, byday.map { x => StatDay(x._1, x._2) }.toArray)
  }(slickExecutionContext)

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
      "callback_url" -> apiSslUrl(routes.ElectionsApi.tallydone(electionId).url),
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
  private def apiSslUrl(suffix: String) = {
    s"$urlSslRoot" + suffix
  }
}
