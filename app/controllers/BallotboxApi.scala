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

import play.api.libs.json._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent._
import java.sql.Timestamp

/**
  * Ballotbox api
  *
  */
object BallotboxApi extends Controller with Response {

  val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")

  /** cast a vote, performs several validations, see vote.validate */
  def vote(electionId: Long, voterId: String) =
    HAction("vote-$0-$1", List(electionId, voterId)).async(BodyParsers.parse.json) { request => Future {

val globalStart = System.nanoTime()

    val voteValue = request.body.validate[VoteDTO]

      voteValue.fold (

      errors => BadRequest(response(s"Invalid vote json $errors")),

      vote => {
        try {
          DB.withSession { implicit session =>
            // val election = Elections.findById(electionId).get
var startTime = System.nanoTime()
            val election = DAL.elections.findByIdWithSession(electionId).get
var endTime = System.nanoTime()
val dbPk = (endTime - startTime) / 1000000.0
            if(election.state == Elections.STARTED) {

              val pksJson = Json.parse(election.pks.get)
              val pksValue = pksJson.validate[Array[PublicKey]]

              pksValue.fold (

                errors => InternalServerError(error(s"Failed reading pks for vote", ErrorCodes.PK_ERROR)),

                pks => {
startTime = System.nanoTime()
                  val validated = vote.validate(pks, true)
endTime = System.nanoTime()
val voteValidate = (endTime - startTime) / 1000000.0

                  // val result = Votes.insert(validated)
startTime = System.nanoTime()
                  val result = DAL.votes.insertWithSession(validated)
endTime = System.nanoTime()
val dbCast = (endTime - startTime) / 1000000.0

val total = (System.nanoTime() - globalStart) / 1000000.0
val tot = dbPk + voteValidate + dbCast

println(s"dbPk $dbPk, voteValidate $voteValidate, dbCast $dbCast, tot $tot, $total")
                  Ok(response(result))
                }
              )
            }
            else {
              BadRequest(response(s"Election is not open"))
            }
          }
        }
        catch {
          case v:ValidationException => BadRequest(response(s"Failed validating vote, $v"))
          case n:NoSuchElementException => BadRequest(response(s"No election found with id $electionId"))
        }
      }
    )
  }(slickExecutionContext)}

  /** check that a given hash is present in the ballotbox */
  def checkHash(electionId: Long, hash: String) =
    HAction("vote-$0-$1", List(electionId, hash)).async(BodyParsers.parse.json) { request => Future {

    val result = DAL.votes.checkHash(electionId, hash)
    result match {
      case Some(vote) => Ok(response(vote))
      case _ => NotFound(response("Hash not found"))
    }
  }(slickExecutionContext)}

  /** dump ciphertexts, goes to the private datastore of the election, this is an admin only command */
  def dumpVotes(electionId: Long) = HAction("admin-$0", List(electionId)).async { request =>
    dumpTheVotes(electionId).map { x =>
      Ok(response(0))
    }
  }

  // FIXME dont need to do timestamp comparison as the order is guaranteed by the DB
  /** dumps votes in batches, goes to the private datastore of the election. Also called by electionapi */
  def dumpTheVotes(electionId: Long) = DB.withSession { implicit session => Future {
    Logger.info(s"dumping votes for election $electionId")
    val batchSize: Int = Play.current.configuration.getInt("app.dump.batchsize").getOrElse(100)
    val count = DAL.votes.countForElectionWithSession(electionId)
    val batches = (count / batchSize) + 1
    // in the current implementation we may hold a large number of timestamps
    val ids = scala.collection.mutable.Set[String]()

    val out = Datastore.getVotesStream(electionId)

    for(i <- 1 to batches) {
      val drop = (i - 1) * batchSize
      val next = DAL.votes.findByElectionIdRangeWithSession(electionId, drop, batchSize)
      // filter duplicates
      val noDuplicates = next.filter { vote =>
        if(ids.contains(vote.voter_id)) {
          false
        } else {
          ids += vote.voter_id
          true
        }
      }
      // TODO filter by voter id's

      // eo format is new line separated list of votes
      // we add an extra \n as otherwise there will be no separation between batches
      if(noDuplicates.length > 0) {
        val content = noDuplicates.map(_.vote).mkString("\n") + "\n"
        out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      }
    }

    out.close()
  }}

  /*-------------------------------- privates  --------------------------------*/

}