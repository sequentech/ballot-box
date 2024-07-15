/**
 * This file is part of ballot_box.
 * Copyright (C) 2014-2016  Sequent Tech Inc <legal@sequentech.io>

 * ballot_box is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * ballot_box  is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.
**/
package controllers

import models._
import utils._
import utils.JsonFormatters._
import utils.Response

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.db.slick.DB
import play.libs.Akka
import play.api.http.{Status => HTTP}
import play.api.libs.ws._

import play.api.libs.json._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent._
import scala.sys.process._
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
  val maxRevotes = Play.current.configuration.getInt("app.api.max_revotes").getOrElse(20)
  val adminEnvBin = Play.current.configuration.getString("app.scripts.adminEnv").getOrElse("./admin/admin_env.sh")
  val voteCallbackUrl = Play.current.configuration.getString("app.callbacks.vote").
    flatMap { vote_str =>
      if (vote_str.length > 0) {
        Some(vote_str)
      } else {
        None
      }
    }
  val boothSecret = Play.current.configuration.getString("elections.auth.secret").get

  /** cast a vote, performs several validations, see vote.validate */
  def vote(electionId: Long, voterId: String) =
    HAction(voterId, "AuthEvent", electionId, "vote").async(BodyParsers.parse.json) { request => Future {

    val voteValue = request.body.validate[VoteDTO]
    voteValue.fold (

      errors => {
        Logger.warn(s"Invalid vote json $errors")
        BadRequest(error(BallotBoxErrorCodes.MALFORMED_VOTE))
      },

      vote => {

        try {

          DB.withSession { implicit session =>

            val election = DAL.elections.findByIdWithSession(electionId).get
            val votesCast = DAL.votes.countForElectionAndVoter(electionId, voterId)

            if(votesCast >= maxRevotes) {
              Logger.warn(s"Maximum number of revotes reached for voterId $voterId")
              BadRequest(error(BallotBoxErrorCodes.MAX_REVOTES_REACHED))
            }
            else {
              val configJson = Json.parse(election.configuration)
              val presentation = configJson.validate[ElectionConfig].get.presentation
              val gracefulEnd = (
                presentation.extra_options.isDefined &&
                presentation.extra_options.get.allow_voting_end_graceful_period.isDefined &&
                presentation.extra_options.get.allow_voting_end_graceful_period.get == true
              )
              if(
                election.state == Elections.STARTED ||
                election.state == Elections.RESUMED ||
                (
                  election.state == Elections.STOPPED &&
                  gracefulEnd
                )
              ) {

                val pksJson = Json.parse(election.pks.get)
                val pksValue = pksJson.validate[Array[PublicKey]]

                pksValue.fold (

                  errors => {
                    Logger.warn(s"Failed reading pks for vote")
                    InternalServerError(error(BallotBoxErrorCodes.PK_ERROR))
                  },

                  pks => {

                    val validated = vote.validate(pks, true, electionId, voterId)
                    val result = DAL.votes.insertWithSession(validated)
                    val now: Long = System.currentTimeMillis / 1000
                    val expiry: Long = now + 300
                    val message = s"$voterId:AuthEvent:$electionId:RegisterSuccessfulLogin:$expiry:timeout-token:$now"
                    voteCallbackUrl.map {
                      url => postVoteCallback(
                        url
                          .replace("${eid}", electionId+"")
                          .replace("${uid}", voterId)
                        ,
                        message,
                        vote.vote_hash
                      )
                    }
                    Ok(response(validated))
                  }
                )
              }
              else {
                Logger.warn(s"Election is not open")
                BadRequest(error(BallotBoxErrorCodes.ELECTION_NOT_OPEN))
              }
            }
          }
        }
        catch {
          case e: ValidationException => {
            e.printStackTrace()
            Logger.error(s"Failed validating vote, ParseException ${e.getMessage}")
            BadRequest(error(BallotBoxErrorCodes.VALIDATION_ERROR))
          }
          case e: NoSuchElementException => {
            e.printStackTrace()
            Logger.error(s"No election found with id $electionId, exception ${e.getMessage}}")
            BadRequest(error(BallotBoxErrorCodes.ELECTION_NOT_FOUND))
          }
          case e: Throwable => {
            e.printStackTrace()
            Logger.error(s"Unexpected error while casting vote: ${e.getMessage}")
            InternalServerError(error(BallotBoxErrorCodes.UNEXPECTED_ERROR))
          }
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
  def dumpVotes(electionId: Long) = HActionAdmin("", "AuthEvent", electionId, "edit").async { request =>

    dumpTheVotes(electionId).map { x =>
      Ok(response(0))
    }
  }

  /**
   * Dumps votes to the private datastore. Only dumps votes matching in voterids
   * file.
   */
  def dumpVotesWithVoterIdsFile(electionId: Long) = 
    HActionAdmin("", "AuthEvent", electionId, "edit").async 
  {
    request =>
      dumpTheVotes(
        electionId, 
        /** filterVoterIds= */ true, 
        /** dumpValidVoterIds= */ false
      )
      .map { x => Ok(response(0)) }
  }

  /**
   * Dumps votes to the private datastore. Only dumps votes matching the enabled
   * voters in AuthApi.
   */
  def dumpVotesWithAuthapiVoterIds(electionId: Long) =
      HActionAdmin("", "AuthEvent", electionId, "edit").async 
  {
    request =>
      dumpTheVotes(
        electionId, 
        /** filterVoterIds= */ true, 
        /** dumpValidVoterIds= */ true
      )
      .map { x => Ok(response(0)) }
  }

  /**
   * Dumps votes to the private datastore of the election. 
   * Called by electionapi.
   */
  def dumpTheVotes(
    electionId: Long,
    filterVoterIds: Boolean = false,
    dumpValidVoterIds: Boolean = true
  ) = Future 
  {
      DB.withSession { implicit session =>
        val election = DAL.elections.findByIdWithSession(electionId).get
        val hasWeightedVoting = election.weightedVotingField.isDefined
        /*val hasSegmentedMixing = (
          election.segmentedMixing.isDefined && election.segmentedMixing.get
        )*/
      /*if (hasSegmentedMixing)
      {
        // Apply segmented mixing for this election
        // 1. Dump categorized ballots
        val votesPath = Datastore.getPath(electionId, Datastore.CIPHERTEXTS)
        val categorizedVotesPath = Datastore.getPath(
          electionId,
          Datastore.CATEGORIZED_CIPHERTEXTS
        )
        val electionConfigPath = Datastore.getPath(
          electionId,
          Datastore.CATEGORY_ELECTION_CONFIG
        )

        val dumpCommand = if (filterVoterIds) {
          Seq(
            s"$adminEnvBin",
            "python3",
            "./admin/dump_categorized_votes.py",
            "--election-id",
            s"$electionId",
            "--output-ballots-path",
            s"$categorizedVotesPath",
            "--election-config-path",
            s"$electionConfigPath"
          )
        } else {
          Seq(
            s"$adminEnvBin",
            "python3",
            "./admin/dump_categorized_votes.py",
            "--election-id",
            s"$electionId",
            "--output-ballots-path",
            s"$categorizedVotesPath",
            "--election-config-path",
            s"$electionConfigPath",
            "--active-voters-only"
          )
        }

        Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting encrypted votes:\n '$dumpCommand'")
        val dumpCommandOutput = dumpCommand.!!
        Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting encrypted votes: command returns\n$dumpCommandOutput")

        // 2. Segment encrypted ballots
        val segmentVotesCommand = Seq(
          s"$adminEnvBin",
          "python3",
          "./admin/segment_ballots.py",
          "--election-config",
          s"$electionConfigPath",
          "--input-ballots",
          s"$categorizedVotesPath",
          "--output-ballots",
          s"$votesPath"
        )

        Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): segmenting encrypted votes:\n '$segmentVotesCommand'")
        val segmentVotesCommandOutput = segmentVotesCommand.!!
        Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): segmenting encrypted votes: command returns\n$segmentVotesCommandOutput")

      } else {*/
        val voteIdsPath = Datastore.getPath(electionId, Datastore.VOTERIDS)
        val outputBallotsPath = Datastore.getPath(electionId, Datastore.CIPHERTEXTS)

        val baseDumpCommand = Seq(
          s"$adminEnvBin",
          "python3",
          "./admin/dump_votes.py",
          "--election-id",
          s"$electionId",
          "--output-ballots-path",
          s"$outputBallotsPath"
        )
        val filterVotersSeq = 
          if (filterVoterIds)
            Seq(
              "--voters-info-path",
              s"$voteIdsPath",
              "--active-voters-only"
            )
          else
            Seq()
        val weightedVotingSeq =
          if (hasWeightedVoting)
            Seq(
              "--vote-weight-column-name",
              s"${election.weightedVotingField.get}"
            )
          else
            Seq()
        val dumpCommand = baseDumpCommand ++ filterVotersSeq ++ weightedVotingSeq
        Logger.info(
          s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): calling:\n '$dumpCommand'"
        )
        val dumpCommandOutput = dumpCommand.!!
        Logger.info(
          s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): calling:\n '$dumpCommand'\ncalling command returns\n$dumpCommandOutput"
        )
      //}
    }
  }

  private def postVoteCallback(url: String, message: String, vote_hash: String) = {
    try {
      println(s"posting to $url")
      val hmac = Crypto.hmac(boothSecret, message)
      val khmac = s"khmac:///sha-256;$hmac/$message"
      val f = WS.url(url)
        .withHeaders(
          "Accept" -> "application/json",
          "Authorization" -> khmac,
          "BallotTracker" -> vote_hash)
        .post(Results.EmptyContent())
        .map { resp =>
          if(resp.status != HTTP.ACCEPTED) {
            Logger.warn(s"callback url returned status ${resp.status} with body ${resp.body} and khmac ${khmac}")
          }
        }
      f.recover {
        case t: Throwable => {
          t.printStackTrace()
          Logger.warn(s"Exception caught when posting to callback $t")
        }
      }
    }
    catch {
      case t:Throwable => {
        t.printStackTrace()
        Logger.warn(s"Exception caught when posting to callback $t")
      }
    }
  }
}