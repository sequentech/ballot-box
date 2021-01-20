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
package models

import play.api.db.slick.DB
import play.api.cache.Cache
import play.api.db.slick.Config.driver.simple._
import play.api.Play.current
import play.api._

import java.sql.Timestamp

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
        // set in cache if found
        election.map(Cache.set(key(id), _))
        election
      }
    }

    def count: Int = DB.withSession { implicit session =>
      Elections.count
    }

    def insert(election: Election) = DB.withSession { implicit session =>
      Cache.remove(key(election.id))
      Elections.insert(election)
    }

    def update(id: Long, election: Election) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.update(id, election)
    }

    def setStartDate(id: Long, startDate: Timestamp) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.setStartDate(id, startDate)
    }

    def setStopDate(id: Long, endDate: Timestamp) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.setStopDate(id, endDate)
    }

    def setTallyDate(id: Long, tallyDate: Timestamp) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.setTallyDate(id, tallyDate)
    }

    def insertWithSession(election: Election)(implicit s:Session) = DB.withSession { implicit session =>
      Cache.remove(key(election.id))
      Elections.insert(election)
    }

    def updateState(id: Long, state: String) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.updateState(id, state)
    }

    def allowTally(id: Long) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.allowTally(id)
    }

    def updatePublishedResults(id: Long, results: String) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.updatePublishedResults(id, results)
    }

    def updateResults(id: Long, results: String, updateStatus: Boolean) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.updateResults(id, results, updateStatus)
    }

    def updateBallotBoxesResultsConfig(id: Long, config: String)
    = DB.withSession
    {
      implicit session =>
        Cache.remove(key(id))
        Elections.updateBallotBoxesResultsConfig(id, config)
    }

    def updateResultsConfig(id: Long, config: String)
    = DB.withSession
    {
      implicit session =>
        Cache.remove(key(id))
        Elections.updateResultsConfig(id, config)
    }

    def updateConfig(
      id: Long, 
      config: String, 
      start: Option[Timestamp], 
      end: Option[Timestamp]
    ) = DB.withSession 
    { 
      implicit session =>
        Cache.remove(key(id))
        Elections.updateConfig(id, config, start, end)
    }

    def setPublicKeys(id: Long, pks: String) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.setPublicKeys(id, pks)
    }

    def delete(id: Long) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.delete(id)
    }

    private def key(id: Long) = s"election.$id"
  }
}
