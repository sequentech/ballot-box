package models

import java.sql.Timestamp

import play.api.Play.current
import play.api.db.slick.Config.driver.simple._

import scala.slick.lifted.Tag

case class Vote(id: Option[Long], electionId: String, voterId: String, vote: String, voteHash: String, created: Timestamp)
case class Election(id: Option[Long], electionId: String, configuration: String, startDate: Timestamp, endDate: Timestamp)

class Votes(tag: Tag) extends Table[Vote](tag, "vote") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def electionId = column[String]("election_id", O.NotNull, O.DBType("text"))
  def voterId = column[String]("voter_id", O.NotNull, O.DBType("text"))
  def vote = column[String]("vote", O.NotNull, O.DBType("text"))
  def voteHash = column[String]("vote_hash",  O.NotNull, O.DBType("text"))
  def created = column[Timestamp]("created", O.NotNull)
  def * = (id.?, electionId, voterId, vote, voteHash, created) <> (Vote.tupled, Vote.unapply _)
}

object Votes {

  val votes = TableQuery[Votes]

  def insert(vote: Vote)(implicit s: Session) = {
    votes.insert(vote)
  }
}

class Elections(tag: Tag) extends Table[Election](tag, "election") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def electionId = column[String]("election_id", O.NotNull, O.DBType("text"))
  def configuration = column[String]("configuration", O.NotNull, O.DBType("text"))
  def startDate = column[Timestamp]("start_date", O.NotNull)
  def endDate = column[Timestamp]("end_date", O.NotNull)
  def * = (id.?, electionId, configuration, startDate, endDate) <> (Election.tupled, Election.unapply _)
}

object Elections {

  val elections = TableQuery[Elections]

  def findById(id: Long)(implicit s: Session): Option[Election] = elections.filter(_.id === id).firstOption

  def count(implicit s: Session): Int =
    Query(elections.length).first

  def insert(election: Election)(implicit s: Session): Int = {
    elections.insert(election)
  }

  def update(id: Long, election: Election)(implicit s: Session) = {
    val electionToWrite = election.copy(Some(id))
    elections.filter(_.id === id).update(electionToWrite)
  }

  def delete(id: Long)(implicit s: Session) = {
    elections.filter(_.id === id).delete
  }
}

case class ElectionConfig(election_id: String, director: String, authorities: Array[String], title: String,
  url: String, description: String, questions_data: Array[Question], voting_start_date: Timestamp, voting_end_date: Timestamp,
  is_recurring: Boolean, extra: Array[String])

case class Question(question: String, tally_type: String, answers: Array[Answer], max: Int, min: Int)
case class Answer(a: String, details: String, value: String)

case class ElectionPks(pks: Array[String])
case class PublicKey(election_id: String)