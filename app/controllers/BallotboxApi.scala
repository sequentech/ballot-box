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
  * Provides ballot casting, hash checking and ballotbox vote dumping
  *
  * Thread pool isolation implemented via futures
  */
object BallotboxApi extends Controller with Response {

  val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")
  val maxRevotes = Play.current.configuration.getInt("app.api.max_revotes").getOrElse(5)

  /** cast a vote, performs several validations, see vote.validate */
  def vote(electionId: Long, voterId: String) =
    HAction(voterId, "AuthEvent", electionId, "vote").async(BodyParsers.parse.json) { request => Future {

    val voteValue = request.body.validate[VoteDTO]
    voteValue.fold (

      errors => BadRequest(response(s"Invalid vote json $errors")),

      vote => {

        try {

          DB.withSession { implicit session =>

            val election = DAL.elections.findByIdWithSession(electionId).get
            val votesCast = DAL.votes.countForElectionAndVoter(electionId, voterId)

            if(votesCast >= maxRevotes) {
              Logger.warn(s"Maximum number of revotes reached for voterId $voterId")
              BadRequest(response(s"Maximum number of revotes reached"))
            }
            else {

              if(election.state == Elections.STARTED || election.state == Elections.CREATED) {

                val pksJson = Json.parse(election.pks.get)
                val pksValue = pksJson.validate[Array[PublicKey]]

                pksValue.fold (

                  errors => InternalServerError(error(s"Failed reading pks for vote", ErrorCodes.PK_ERROR)),

                  pks => {

                    val validated = vote.validate(pks, true, electionId, voterId)
                    val result = DAL.votes.insertWithSession(validated)
                    Ok(response(result))
                  }
                )
              }
              else {
                BadRequest(response(s"Election is not open"))
              }
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
  def checkHash(electionId: Long, hash: String) = Action.async { request => Future {
    val result = DAL.votes.checkHash(electionId, hash)
    result match {
      case Some(vote) => Ok(response(vote.copy(voter_id = "")))
      case _ => NotFound(response("Hash not found"))
    }
  }(slickExecutionContext)}

  /** dump ciphertexts, goes to the private datastore of the election, this is an admin only command */
  def dumpVotes(electionId: Long) = HAction("", "AuthEvent", electionId, "edit").async { request =>

    dumpTheVotes(electionId).map { x =>
      Ok(response(0))
    }
  }

  /** request a tally, dumps votes to the private ds. Only tallies votes matching passed in voter ids */
  def dumpVotesWithVoterIds(electionId: Long) = HAction("", "AuthEvent", electionId, "edit").async(BodyParsers.parse.json) { request =>

    val validIds = request.body.asOpt[List[String]].map(_.toSet)

    dumpTheVotes(electionId, validIds).map { x =>
      Ok(response(0))
    }
  }

  /** dumps votes in batches, goes to the private datastore of the election. Also called by electionapi */
  def dumpTheVotes(electionId: Long, validVoterIds: Option[Set[String]] = None) = Future {

    Logger.info(s"dumping votes for election $electionId")

    val batchSize: Int = Play.current.configuration.getInt("app.dump.batchsize").getOrElse(100)
    DB.withSession { implicit session =>

      val count = DAL.votes.countForElectionWithSession(electionId)
      val batches = (count / batchSize) + 1
      // in the current implementation we may hold a large number of ids
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

        // filter by voter id's, if present
        val maybeValid = validVoterIds.map( ids => noDuplicates.filter( vote => ids.contains(vote.voter_id)) )
        val valid = maybeValid.getOrElse(noDuplicates)

        // eo format is new line separated list of votes
        // we add an extra \n as otherwise there will be no separation between batches
        if(valid.length > 0) {
          val content = valid.map(_.vote).mkString("\n") + "\n"
          out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }
      }
      out.close()
    }
  }

}