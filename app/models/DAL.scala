package models

import play.api.db.slick.DB
import play.api.cache.Cache
import play.api.db.slick.Config.driver.simple._
import play.api.Play.current

import java.sql.Timestamp

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
      votes.findByElectionIdRange(electionId, drop, take)
    }

    def checkHash(id: Long, hash: String) = DB.withSession { implicit session =>
      Votes.checkHash(id, hash)
    }

    def count: Int = DB.withSession { implicit session =>
      Votes.count
    }

    def countForElection(electionId: Long): Int = DB.withSession { implicit session =>
      Votes.countForElection(electionId)
    }

    private def key(id: Long) = s"vote.$id"
  }

  /** adds a caching layer */
  object elections {
    def findById(id: Long): Option[Election] = DB.withSession { implicit session =>
      findByIdWithSession(id)
    }
    def findByIdWithSession(id: Long)(implicit s: Session): Option[Election] = Cache.getAs[Election](key(id)) match {
      case Some(e) => Some(e)
      case None => Elections.findById(id)
    }

    def count: Int = DB.withSession { implicit session =>
      Elections.count
    }

    def insert(election: Election) = DB.withSession { implicit session =>
      Cache.remove(key(election.id))
      Elections.insert(election)
    }

    def updateState(id: Long, state: String) = DB.withSession { implicit session =>
      Cache.remove(key(id))
      Elections.updateState(id, state)
    }

    def updateConfig(id: Long, config: String, start: Timestamp, end: Timestamp) = DB.withSession { implicit session =>
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