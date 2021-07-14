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
                    val now: Long = System.currentTimeMillis / 1000
                    val message = s"$voterId:AuthEvent:$electionId:RegisterSuccessfulLogin:$now"
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
    if (filterVoterIds) 
    {
      // Filters active voters from authapi
      val voteIdsPath = Datastore.getPath(electionId, Datastore.VOTERIDS)

      // 1. dump valid voter ids, if enabled
      if (dumpValidVoterIds)
      {
        val dumpIdsCommand = Seq(
          "psql",
          "service = authapi",
          "-tAc",
          s"""
SELECT auth_user.username
FROM api_acl
INNER JOIN api_userdata ON api_acl.user_id = api_userdata.id
INNER JOIN auth_user ON auth_user.id = api_userdata.user_id
INNER JOIN api_authevent ON api_authevent.id = '$electionId'
WHERE 
  api_acl.object_id IS NOT NULL 
  AND api_acl.object_type = 'AuthEvent'
  AND api_acl.perm = 'vote'
  AND (
    (
      api_acl.object_id = '$electionId' AND api_authevent.parent_id IS NULL
    ) OR (
      api_acl.object_id = api_authevent.parent_id::text
      AND api_authevent.parent_id IS NOT NULL
      AND api_userdata.children_event_id_list::text LIKE '%$electionId%'
    )
  )
ORDER BY auth_user.username ASC;""",
          "-o",
          s"$voteIdsPath"
        )
  
        Logger.info(s"dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting   voterIds:\n '$dumpIdsCommand'")
        val dumpIdsCommandOutput = dumpIdsCommand.!!
        Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting   voterIds: command returns\n$dumpIdsCommandOutput")
      }

      // 2. Dump all votes.
      // Each line contains first the voter_id, then the vote
      val allCiphertextsPath = Datastore.getPath(electionId, Datastore.ALL_CIPHERTEXTS)
      val dumpAllVotesCommand = Seq(
        "psql",
        "service = agora_elections",
        "-tAc",
        s"SELECT DISTINCT ON (voter_id) voter_id,vote FROM vote WHERE election_id=$electionId ORDER BY voter_id ASC, CREATED DESC;",
        "-o",
        s"$allCiphertextsPath"
      )

      Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting all cipherTexts:\n '$dumpAllVotesCommand'")
      val dumpAllVotesCommandOutput = dumpAllVotesCommand.!!
      Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting all cipherTexts:command returns\n$dumpAllVotesCommandOutput")

      // 3. Filter the votes by voter_id, using the join command
      val votesPath = Datastore.getPath(electionId, Datastore.CIPHERTEXTS)
      val joinVotesCommand = Seq(
        "bash",
        "-lc",
        s"join --nocheck-order $allCiphertextsPath $voteIdsPath -t '|' -o 1.2 > $votesPath"
      )
      Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): filtering cipherTexts:\n '$joinVotesCommand'")
      val joinVotesCommandOutput = joinVotesCommand.!!
      Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): filtering cipherTexts:command returns\n$joinVotesCommandOutput")
    } else {
      // Do not filter active voters
      val votesPath = Datastore.getPath(electionId, Datastore.CIPHERTEXTS)
      val dumpCommand = Seq(
        "psql",
        "service = agora_elections",
        "-tAc",
        s"SELECT DISTINCT ON (voter_id) vote FROM vote WHERE election_id=$electionId ORDER BY voter_id ASC, CREATED DESC;",
        "-o",
        s"$votesPath"
      )

      Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting cipherTexts:\n '$dumpCommand'")
      val dumpCommandOutput = dumpCommand.!!
      Logger.info(s"executing dumpTheVotes(electionId=$electionId, filterVoterIds=$filterVoterIds): getting cipherTexts:command returns\n$dumpCommandOutput")
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
