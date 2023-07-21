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
package models

import play.api.db.slick.DB
import play.api.cache.Cache
import play.api.db.slick.Config.driver.simple._
import play.api.Play.current
import play.api._

import java.sql.Timestamp

import scala.sys.process._

import utils._

/**
  * DAL - data access layer
  *
  * Provides a decoupled interface to persistence functions. Do caching here.
  *
  */
object DAL {

  /** straight mapping to models */
  object votes {

    def insert(vote: Vote) = DB.withSession { implicit session =>
      insertWithSession(vote)
    }
    def insertWithSession(vote: Vote)(implicit s: Session) = {
      Votes.insert(vote)
    }

    def findByVoterId(voterId: String): List[Vote] = DB.withSession { implicit session =>
      Votes.findByVoterId(voterId)
    }

    def findByElectionId(electionId: Long): List[Vote] = DB.withSession { implicit session =>
      Votes.findByElectionId(electionId)
    }

    def findByElectionIdRange(electionId: Long, drop: Long, take: Long): List[Vote] = DB.withSession { implicit session =>
      findByElectionIdRangeWithSession(electionId, drop, take)
    }

    def findByElectionIdRangeWithSession(electionId: Long, drop: Long, take: Long)(implicit s: Session): List[Vote] = {
      Votes.findByElectionIdRange(electionId, drop, take)
    }

    def checkHash(id: Long, hash: String) = DB.withSession { implicit session =>
      Votes.checkHash(id, hash)
    }

    def count: Int = DB.withSession { implicit session =>
      Votes.count
    }

    def isVoteDumpEmpty(electionId: Long): Boolean = {
      val votesPath = Datastore.getPath(electionId, Datastore.CIPHERTEXTS)
      val countVotesCommand = Seq(
        "wc",
        "-l",
        s"$votesPath"
      )
      Logger.info(s"counting votes dumped:\n '$countVotesCommand'")
      val countVotesCommandOutput = countVotesCommand.!!
      Logger.info(s"counting votes dumped returns: $countVotesCommandOutput")
      countVotesCommandOutput.startsWith("0 ")
    }

    def countForElection(electionId: Long): Int = DB.withSession { implicit session =>
      countForElectionWithSession(electionId)
    }

    def countForElectionWithSession(electionId: Long)(implicit s: Session): Int = {
      Votes.countForElection(electionId)
    }

    def countUniqueForElection(electionId: Long): Int = DB.withSession { implicit session =>
      countUniqueForElectionWithSession(electionId)
    }

    def countUniqueForElectionWithSession(electionId: Long)(implicit s: Session): Int = {
      Votes.countUniqueForElection(electionId)
    }

    def countForElectionAndVoter(electionId: Long, voterId: String)(implicit s: Session): Int = {
      Votes.countForElectionAndVoter(electionId,voterId)
    }

    def byDay(electionId: Long): List[(String, Long)] = DB.withSession { implicit session =>
        byDayW(electionId)
    }

    def byDayW(electionId: Long)(implicit s: Session): List[(String, Long)] = {
      Votes.byDay(electionId)
    }

    private def key(id: Long) = s"vote.$id"
  }

  /** adds a caching layer */
  object elections {

    def findById(id: Long): Option[Election] = DB.withSession { implicit session =>
      findByIdWithSession(id)
    }
    def findByIdWithSession(id: Long)(implicit s: Session): Option[Election] = Cache.getAs[Election](key(id)) match {
      case Some(e) => {
        Some(e)
      }
      case None => {
        val election = Elections.findById(id)
        // set in cache if found, with some expiration
        val expirationSeconds = Play.current.configuration
          .getInt("app.cache.expiration_seconds")
          .getOrElse(60)
        election.map(Cache.set(key(id), _, expirationSeconds))
        election
      }
    }

    def count: Int = DB.withSession { implicit session =>
      Elections.count
    }

    def insert(election: Election) = DB.withSession { implicit session =>
      val ret = Elections.insert(election)
      Cache.remove(key(election.id))
      ret
    }

    def update(id: Long, election: Election) = DB.withSession { implicit session =>
      val ret = Elections.update(id, election)
      Cache.remove(key(id))
      ret
    }

    def setPublicCandidates(id: Long, publicCandidates: Boolean) =
      DB.withSession {
        implicit session => {
          val ret = Elections.setPublicCandidates(id, publicCandidates)
          Cache.remove(key(id))
          ret
        }
      }

    def setStartDate(id: Long, startDate: Timestamp) = DB.withSession { implicit session =>
      val ret = Elections.setStartDate(id, startDate)
      Cache.remove(key(id))
      ret
    }

    def setStopDate(id: Long, endDate: Timestamp) = DB.withSession { implicit session =>
      val ret = Elections.setStopDate(id, endDate)
      Cache.remove(key(id))
      ret
    }

    def setTallyDate(id: Long, tallyDate: Timestamp) = DB.withSession { implicit session =>
      val ret = Elections.setTallyDate(id, tallyDate)
      Cache.remove(key(id))
      ret
    }

    def insertWithSession(election: Election)(implicit s:Session) = DB.withSession { implicit session =>
      val ret = Elections.insert(election)
      Cache.remove(key(election.id))
      ret
    }

    def updateState(id: Long, state: String) = DB.withSession { implicit session =>
      val current_state = DAL
        .elections
        .findByIdWithSession(id)
        .get
        .state
      val ret = Elections.updateState(id, current_state, state)
      Cache.remove(key(id))
      ret
    }

    def allowTally(id: Long) = DB.withSession { implicit session =>
      val ret = Elections.allowTally(id)
      Cache.remove(key(id))
      ret
    }

    def updatePublishedResults(id: Long, results: String) = DB.withSession { implicit session =>
      val ret = Cache.remove(key(id))
      Elections.updatePublishedResults(id, results)
      ret
    }

    def updateResults(id: Long, results: String, updateStatus: Boolean) = DB.withSession { implicit session =>
      val ret = Elections.updateResults(id, results, updateStatus)
      Cache.remove(key(id))
      ret
    }

    def updateTallyState(id: Long, state: String) = DB.withSession { implicit session =>
      val ret = Elections.updateTallyState(id, state)
      ret
    }

    def updateBallotBoxesResultsConfig(id: Long, config: String)
    = DB.withSession
    {
      implicit session =>
        val ret = Elections.updateBallotBoxesResultsConfig(id, config)
        Cache.remove(key(id))
        ret
    }

    def updateResultsConfig(id: Long, config: String)
    = DB.withSession
    {
      implicit session =>
        val ret = Elections.updateResultsConfig(id, config)
        Cache.remove(key(id))
        ret
    }

    def updateConfig(
      id: Long, 
      config: String, 
      start: Option[Timestamp], 
      end: Option[Timestamp]
    ) = DB.withSession 
    { 
      implicit session =>
        val ret = Elections.updateConfig(id, config, start, end)
        Cache.remove(key(id))
        ret
    }

    def setPublicKeys(id: Long, pks: String) = DB.withSession { implicit session =>
      val ret = Elections.setPublicKeys(id, pks)
      Cache.remove(key(id))
      ret
    }

    def delete(id: Long) = DB.withSession { implicit session =>
      val ret = Elections.delete(id)
      Cache.remove(key(id))
      ret
    }

    private def key(id: Long) = s"election.$id"
  }
}
