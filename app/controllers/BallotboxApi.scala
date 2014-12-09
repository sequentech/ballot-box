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

/**
  * Ballotbox api
  *
  */
object BallotboxApi extends Controller with Response {

  /** cast a vote, performs several validations, see vote.validate */
  def vote(electionId: Long, voterId: String) = LHAction("vote-$0-$1", List(electionId, voterId)).async(BodyParsers.parse.json) { request =>
    val _vote = request.body.validate[Vote]

    _vote.fold (

      errors => Future {
        BadRequest(Json.toJson(Response(s"Invalid vote json $errors")))
      },

      vote => Future {
        try {
          DB.withSession { implicit session =>
            // val election = Elections.findById(electionId).get
            val election = DAL.elections.findByIdWithSession(electionId).get
            if(election.state == Elections.STARTED) {
              val _pks = Json.parse(election.pks.get)
              val pksParse = _pks.validate[Array[PublicKey]]

              pksParse.fold (

                errors => InternalServerError(error(s"Failed reading pks for vote", ErrorCodes.PK_ERROR)),

                pks => {
                  val validated = vote.validate(pks, true)

                  // val result = Votes.insert(validated)
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
        catch {
          case v:ValidationException => BadRequest(response(s"Failed validating vote, $v"))
          case n:NoSuchElementException => BadRequest(response(s"Failed validating vote, no election found $electionId"))
        }
      }
    )
  }

  /** check that a given hash is present in the ballotbox */
  def checkHash(electionId: Long, hash: String) = LHAction("vote-$0-$1", List(electionId, hash)).async(BodyParsers.parse.json) { request =>
    Future {
      DB.withSession { implicit session =>
        val result = Votes.checkHash(electionId, hash)
        result match {
          case Some(vote) => Ok(response(vote))
          case _ => NotFound(response("Hash not found"))
        }

      }
    }
  }

  /** dump ciphertexts, goes to the private datastore of the election, this is an admin only command */
  def dumpVotes(electionId: Long) = LHAction("admin-$0", List(electionId)).async { request =>
    dumpTheVotes(electionId).map { x =>
      Ok(response(0))
    }
  }

  /** dumps votes in batches, goes to the private datastore of the election. Also called by electionapi */
  def dumpTheVotes(electionId: Long) = DB.withSession { implicit session =>
    Future {
      val batchSize: Int = Play.current.configuration.getInt("app.dump.batchsize").getOrElse(100)
      val count = Votes.countForElection(electionId)
      val batches = (count / batchSize) + 1

      val out = Datastore.getVotesStream(electionId)

      for(i <- 1 to batches) {
        val drop = (i - 1) * batchSize
        val take = i * batchSize
        val next = Votes.findByElectionIdRange(electionId, drop, take)
        // eo format is new line separated list of votes
        val content = next.map(_.vote).mkString("\n")
        out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      }

      out.close()
    }
  }

  /*-------------------------------- privates  --------------------------------*/
}