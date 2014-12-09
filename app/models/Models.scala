package models

import java.sql.Timestamp

import play.api.Play.current
import play.api.db.slick.Config.driver.simple._

import scala.slick.lifted.Tag
import utils.Crypto
import play.api.libs.json._
import utils.JsonFormatters._


case class Vote(id: Option[Long], election_id: Long, voter_id: String, vote: String, hash: String, created: Timestamp) {
  def validate(pks: Array[PublicKey], checkResidues: Boolean) = {
    val json = Json.parse(vote)
    val encryptedValue = json.validate[EncryptedVote]

    encryptedValue.fold (
      errors => throw new ValidationException(s"Error parsing vote json: $errors"),
      encrypted => {

        encrypted.validate(pks, checkResidues)

        val hashed = Crypto.sha256(vote)

        if(hashed != hash) throw new ValidationException("Hash mismatch")

        copy(vote = json.toString)
      }
    )
  }
}
case class Election(id: Long, configuration: String, state: String, startDate: Timestamp, endDate: Timestamp, pks: Option[String])

class Votes(tag: Tag) extends Table[Vote](tag, "vote") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def electionId = column[Long]("election_id", O.NotNull)
  def voterId = column[String]("voter_id", O.NotNull, O.DBType("text"))
  def vote = column[String]("vote", O.NotNull, O.DBType("text"))
  def hash = column[String]("hash",  O.NotNull, O.DBType("text"))
  def created = column[Timestamp]("created", O.NotNull)
  def * = (id.?, electionId, voterId, vote, hash, created) <> (Vote.tupled, Vote.unapply _)
}

object Votes {

  val votes = TableQuery[Votes]

  def insert(vote: Vote)(implicit s: Session) = {
    (votes returning votes.map(_.id)) += vote
  }

  def findByVoterId(voterId: String)(implicit s: Session): List[Vote] = votes.filter(_.voterId === voterId).list

  def findByElectionId(electionId: Long)(implicit s: Session): List[Vote] = votes.filter(_.electionId === electionId).list

  def findByElectionIdRange(electionId: Long, drop: Long, take: Long)(implicit s: Session): List[Vote] = {
    votes.filter(_.electionId === electionId).drop(drop).take(take).list
  }

  def checkHash(id: Long, hash: String)(implicit s: Session): Option[Vote] = votes.filter(_.id === id).filter(_.hash === hash).firstOption

  // def count(implicit s: Session): Int = Query(votes.length).first
  def count(implicit s: Session): Int = votes.length.run

  def countForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).length.run
}

class Elections(tag: Tag) extends Table[Election](tag, "election") {
  def id = column[Long]("id", O.PrimaryKey)
  def configuration = column[String]("configuration", O.NotNull, O.DBType("text"))
  def state = column[String]("state", O.NotNull)
  def startDate = column[Timestamp]("start_date", O.NotNull)
  def endDate = column[Timestamp]("end_date", O.NotNull)
  def pks = column[String]("pks", O.Nullable, O.DBType("text"))
  def * = (id, configuration, state, startDate, endDate, pks.?) <> (Election.tupled, Election.unapply _)
}

object Elections {
  val REGISTERED = "registered"
  val CREATED = "created"
  val CREATE_ERROR = "create_error"
  val TALLY_ERROR = "tally_error"
  val TALLY_OK = "tally_ok"
  val STARTED = "started"
  val STOPPED = "stopped"

  val elections = TableQuery[Elections]

  def findById(id: Long)(implicit s: Session): Option[Election] = elections.filter(_.id === id).firstOption

  def count(implicit s: Session): Int = elections.length.run

  def insert(election: Election)(implicit s: Session) = {
    (elections returning elections.map(_.id)) += election
  }

  def update(theId: Long, election: Election)(implicit s: Session) = {
    val electionToWrite = election.copy(id = theId)
    elections.filter(_.id === theId).update(electionToWrite)
  }

  def updateState(id: Long, state: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.state).update(state)
  }

  def updateConfig(id: Long, config: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.configuration).update(config)
  }

  def setPublicKeys(id: Long, pks: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => (e.state, e.pks)).update(CREATED, pks)
  }

  def delete(id: Long)(implicit s: Session) = {
    elections.filter(_.id === id).delete
  }
}

case class ElectionConfig(
  election_id: Long, director: String, authorities: Array[String], title: String,
  url: String, description: String, questions_data: Array[Question], voting_start_date: Timestamp,
  voting_end_date: Timestamp,
  is_recurring: Boolean, extra: Array[String])

case class Question(question: String, tally_type: String, answers: Array[Answer], max: Int, min: Int)
case class Answer(a: String, details: String, value: String)

case class CreateResponse(status: String, session_data: Array[PublicKeySession])
// {"status":"error","data":{"message":"election tally failed for some reason"},"reference":{"action":"POST /tally","election_id":"49"}}
case class TallyData(tally_url: String, tally_hash: String)
case class TallyResponse(status: String, data: TallyData)

case class PublicKeySession(pubkey: PublicKey, session_id: String)
case class PublicKey(q: BigInt, p: BigInt, y:BigInt, g: BigInt)

case class EncryptedVote(a: String, choices: Array[Choice], election_hash: ElectionHash, issue_date: String, proofs: Array[Popk]) {
  def validate(pks: Array[PublicKey], checkResidues: Boolean) = {

    if(a != "encrypted-vote-v1") throw new ValidationException("Unexpected a value")

    if(election_hash.a != "hash/sha256/value") throw new ValidationException ("Unexpected a value on election hash")

    if(checkResidues) {
      choices.zipWithIndex.foreach { case (choice, index) =>
        choice.validate(pks(index))
      }
    }
    checkPopk(pks)
  }

  def checkPopk(pks: Array[PublicKey]) = {

    proofs.zipWithIndex.foreach { case (proof, index) =>
      val choice = choices(index)

      val toHash = s"${choice.alpha.toString}/${proof.commitment.toString}"
      val hashed = Crypto.sha256(toHash)
      val expected = BigInt(hashed, 16)

      if (!proof.challenge.equals(expected)) {
        throw new ValidationException("Popk hash mismatch")
      }

      val pk = pks(index)

      val first = pk.g.modPow(proof.response, pk.p)
      val second = (choice.alpha.modPow(proof.challenge, pk.p) * proof.commitment).mod(pk.p)

      if(!first.equals(second)) {
        throw new ValidationException("Failed verifying popk")
      }
    }
  }
}

case class Choice(alpha: BigInt, beta: BigInt) {
  def validate(pk: PublicKey) = {
    if(!Crypto.quadraticResidue(alpha, pk.p)) throw new ValidationException("Alpha quadratic non-residue")
    if(!Crypto.quadraticResidue(beta, pk.p)) throw new ValidationException("Beta quadratic non-residue")
  }
}
case class Popk(challenge: BigInt, commitment: BigInt, response: BigInt)
case class ElectionHash(a: String, value: String)

class ValidationException(message: String) extends Exception(message)