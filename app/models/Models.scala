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

import utils.Crypto
import utils.JsonFormatters._
import utils.Validator._
import utils.Validator
import utils.ValidationException

import play.api.Play
import play.api.db.slick.Config.driver.simple._
import scala.slick.lifted.Tag
import play.api.libs.json._

import java.sql.Timestamp
import java.util.Date

import scala.slick.jdbc.{GetResult, StaticQuery => Q}

/** vote object */
case class Vote(id: Option[Long], election_id: Long, voter_id: String, vote: String, hash: String, created: Timestamp)

/** relational representation of votes */
class Votes(tag: Tag) extends Table[Vote](tag, "vote") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def electionId = column[Long]("election_id", O.NotNull)
  def voterId = column[String]("voter_id", O.NotNull, O.DBType("text"))
  def vote = column[String]("vote", O.NotNull, O.DBType("text"))
  def hash = column[String]("hash",  O.NotNull, O.DBType("text"))
  def created = column[Timestamp]("created", O.NotNull)
  def * = (id.?, electionId, voterId, vote, hash, created) <> (Vote.tupled, Vote.unapply _)
}

/** data access object for votes */
object Votes {

  val votes = TableQuery[Votes]

  def insert(vote: Vote)(implicit s: Session) = {
    (votes returning votes.map(_.id)) += vote
  }

  def byDay(id: Long)(implicit s: Session): List[(String, Long)] = {
    val q = Q[Long, (String, Long)] + "select to_char(created, 'YYYY-MM-DD'), count(id) from vote where election_id=? group by to_char(created, 'YYYY-MM-DD') order by to_char(created, 'YYYY-MM-DD') desc"
    q(id).list
  }

  def findByVoterId(voterId: String)(implicit s: Session): List[Vote] = votes.filter(_.voterId === voterId).list

  def findByElectionId(electionId: Long)(implicit s: Session): List[Vote] = votes.filter(_.electionId === electionId).list

  def findByElectionIdRange(electionId: Long, drop: Long, take: Long)(implicit s: Session): List[Vote] = {
    votes.filter(_.electionId === electionId).sortBy(_.created.desc).drop(drop).take(take).list
  }

  def checkHash(electionId: Long, hash: String)(implicit s: Session): Option[Vote] = {
    val vote = votes.filter(_.electionId === electionId).filter(_.hash === hash).firstOption

    // we make sure the hash corresponds to the last vote, otherwise return None
    vote.flatMap { v =>
      val latest = votes.filter(_.electionId === electionId).filter(_.voterId === v.voter_id).sortBy(_.created.desc).firstOption
      latest.filter(_.hash == hash)
    }
  }

  // def count(implicit s: Session): Int = Query(votes.length).first
  def count(implicit s: Session): Int = votes.length.run

  def countForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).length.run
  def countUniqueForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).groupBy(v=>v.voterId).map(_._1).length.run

  def countForElectionAndVoter(electionId: Long, voterId: String)(implicit s: Session): Int = {
    votes.filter(_.electionId === electionId).filter(_.voterId === voterId).length.run
  }
}

/** election object */
case class Election(
  id: Long,
  configuration: String,
  state: String,
  startDate: Option[Timestamp],
  endDate: Option[Timestamp],
  pks: Option[String],
  resultsConfig: Option[String],
  ballotBoxesResultsConfig: Option[String],
  results: Option[String],
  resultsUpdated: Option[Timestamp],
  virtual: Boolean,
  logo_url: Option[String])
{

  def getDTO =
  {
    var configJson = Json.parse(configuration)
    if (!configJson.as[JsObject].keys.contains("layout")) {
        configJson = configJson.as[JsObject] + ("layout" -> Json.toJson("simple"))
    }

    if (!configJson.as[JsObject].keys.contains("virtual")) {
        configJson = configJson.as[JsObject] + ("virtual" -> Json.toJson(virtual))
    }

    if (!configJson.as[JsObject].keys.contains("resultsConfig")) {
        configJson = configJson.as[JsObject] + ("resultsConfig" -> Json.toJson(resultsConfig))
    }

    if (!configJson.as[JsObject].keys.contains("logo_url")) {
        configJson = configJson.as[JsObject] + ("logo_url" -> Json.toJson(logo_url))
    }

    var config = configJson.validate[ElectionConfig].get
    var res = None: Option[String]
    var resUp = None: Option[Timestamp]

    if (state == Elections.RESULTS_PUB) {
        res = results
        resUp = resultsUpdated
    }

    ElectionDTO(
      id,
      config,
      state,
      startDate,
      endDate,
      pks,
      resultsConfig,
      res,
      resUp,
      virtual,
      logo_url)
  }
}

/** relational representation of elections */
class Elections(tag: Tag)
  extends Table[Election](tag, "election")
{
  def id = column[Long]("id", O.PrimaryKey)
  def configuration = column[String]("configuration", O.NotNull, O.DBType("text"))
  def state = column[String]("state", O.NotNull)
  def startDate = column[Timestamp]("start_date", O.Nullable)
  def endDate = column[Timestamp]("end_date", O.Nullable)
  def pks = column[String]("pks", O.Nullable, O.DBType("text"))
  def resultsConfig = column[String]("results_config", O.Nullable, O.DBType("text"))
  def ballotBoxesResultsConfig = column[String]("ballot_boxes_results_config", O.Nullable, O.DBType("text"))
  def results = column[String]("results", O.Nullable, O.DBType("text"))
  def resultsUpdated = column[Timestamp]("results_updated", O.Nullable)
  def virtual = column[Boolean]("virtual")
  def logo_url = column[String]("logo_url", O.Nullable, O.DBType("text"))

  def * = (
    id,
    configuration,
    state,
    startDate.?,
    endDate.?,
    pks.?,
    resultsConfig.?,
    ballotBoxesResultsConfig.?,
    results.?,
    resultsUpdated.?,
    virtual,
    logo_url.?
  ) <> (Election.tupled, Election.unapply _)
}

/** data access object for elections */
object Elections {
  val REGISTERED = "registered"
  val CREATED = "created"
  val CREATE_ERROR = "create_error"
  val STARTED = "started"
  val STOPPED = "stopped"
  val TALLY_OK = "tally_ok"
  val TALLY_ERROR = "tally_error"
  val RESULTS_OK = "results_ok"
  val DOING_TALLY = "doing_tally"
  val RESULTS_PUB = "results_pub"

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
    state match {
      case STARTED => elections.filter(_.id === id).map(e => (e.state, e.startDate)).update(state, new Timestamp(new Date().getTime))
      case STOPPED => elections.filter(_.id === id).map(e => (e.state, e.endDate)).update(state, new Timestamp(new Date().getTime))
      case _ => elections.filter(_.id === id).map(e => e.state).update(state)
    }
  }

  def setStartDate(id: Long, startDate: Timestamp)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.startDate).update(startDate)
  }

  def setStopDate(id: Long, endDate: Timestamp)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.endDate).update(endDate)
  }

  def setTallyDate(id: Long, tallyDate: Timestamp)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.resultsUpdated).update(tallyDate)
  }

  def updateResults(id: Long, results: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => (e.state, e.results, e.resultsUpdated))
    .update(Elections.RESULTS_OK, results, new Timestamp(new Date().getTime))
  }

  def updateConfig(id: Long, config: String, start: Option[Timestamp], end: Option[Timestamp])(implicit s: Session) = {
    if (start.isEmpty && end.isEmpty) {
      elections.filter(_.id === id).map(e => (e.configuration)).update(config)
    } else if (start.isDefined && end.isEmpty) {
      elections.filter(_.id === id).map(e => (e.configuration, e.startDate)).update(config, start.get)
    } else if (start.isEmpty && end.isDefined) {
      elections.filter(_.id === id).map(e => (e.configuration,  e.endDate)).update(config, end.get)
    } else {
      elections.filter(_.id === id).map(e => (e.configuration, e.startDate, e.endDate)).update(config, start.get, end.get)
    }
    
  }

  def updateBallotBoxesResultsConfig(id: Long, ballotBoxesResultsConfig: String)
  (implicit s: Session) =
  {
    elections.filter(_.id === id).map(e => (e.ballotBoxesResultsConfig))
      .update(ballotBoxesResultsConfig)
  }

  def setPublicKeys(id: Long, pks: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => (e.state, e.pks)).update(CREATED, pks)
  }

  def delete(id: Long)(implicit s: Session) = {
    elections.filter(_.id === id).delete
  }
}

/*-------------------------------- transient models  --------------------------------*/

case class StatDay(day: String, votes: Long)
case class Stats(totalVotes: Long, votes: Long, days: Array[StatDay])

/** Used to receive dates in setStartDate and setStopDate APIs */
case class DateDTO(date: String)
{
  def validate() = {
    validateStringLength(date, SHORT_STRING, s"date string too large: $date")
  }
}

/** used to return an election with config in structured form */
case class ElectionDTO(
  id: Long,
  configuration: ElectionConfig,
  state: String,
  startDate: Option[Timestamp],
  endDate: Option[Timestamp],
  pks: Option[String],
  resultsConfig: Option[String],
  results: Option[String],
  resultsUpdated: Option[Timestamp],
  virtual: Boolean,
  logo_url: Option[String]
)

/** an election configuration defines an election */
case class ElectionConfig(id: Long, layout: String, director: String, authorities: Array[String], title: String, description: String,
  questions: Array[Question], start_date: Option[Timestamp], end_date: Option[Timestamp], presentation: ElectionPresentation, extra_data: Option[String], resultsConfig: Option[String], virtual: Boolean, virtualSubelections: Option[Array[Long]], logo_url: Option[String])
{

  /**
    * validates an election config, this does two things:
    *
    * 1) validation: throws ValidationException if the content cannot be made valid
    *
    * 2) sanitation: transforms the content that can be made valid
    *
    * returns a valid ElectionConfig
    *
    */
  def validate(peers: Map[String, JsObject], id2: Long) = {

    assert(id >= 0, s"Invalid id $id")
    validateIdentifier(layout, "invalid layout")
    assert(id == id2, s"Invalid id $id")
    // validate authorities
    val auths = (director +: authorities).toSet

    assert(auths.size >= 2, s"Need at least two authorities (${auths.size})")

    // make sure that all requested authorities are available as peers
    auths.foreach { auth =>
      assert(peers.contains(auth), "One or more authorities were not found")
    }

    validateStringLength(title, LONG_STRING, s"title too large: $title")
    assert(description.length <= LONG_STRING, "description too long")
    val descriptionOk = sanitizeHtml(description)

    assert(questions.size >= 1, "need at least one question")
    val questionsOk = questions.map(_.validate())

    // check maximum number of questions
    var maxNumQuestions = Play.current.configuration.getInt("election.limits.maxNumQuestions").getOrElse(20)
    assert(
      questions.size <= maxNumQuestions,
      s"too many questions: questions.size(${questions.size}) > maxNumQuestions($maxNumQuestions)"
    )

    // TODO
    // start_date
    // end_date

    val presentationOk = presentation.validate()

    // virtualSubelections setting only makes sense currently in dedicated
    // installations because we do not check the admin ownership of those,
    // and that's why support for subelections can be disabled via a config
    // setting
    val virtualElectionsAllowed = Play.current.configuration
      .getBoolean("election.virtualElectionsAllowed")
      .getOrElse(false)

    assert(
      !virtual || virtualElectionsAllowed,
      "virtual elections are not allowed"
    )

    assert(
      (
        !virtual &&
        (!virtualSubelections.isDefined || virtualSubelections.get.size == 0)
      ) ||
      (
        virtual &&
        virtualSubelections.isDefined &&
        virtualSubelections.get.size > 0
      ),
      "inconsistent virtuality configuration of the election"
    )

    assert(
      !virtualSubelections.isDefined ||
      (virtualSubelections.get.sorted.deep == virtualSubelections.get.deep),
      "subelections must be sorted"
    )

    this.copy(description = descriptionOk, questions = questionsOk, presentation = presentationOk)
  }

  /** returns a json string representation */
  def asString() = {
    Json.stringify(Json.toJson(this))
  }
}

/** defines a question asked in an election */
case class Question(description: String, layout: String, max: Int, min: Int, num_winners: Int, title: String,
  tally_type: String, answer_total_votes_percentage: String, answers: Array[Answer], extra_options: Option[QuestionExtra]) {

  def validate() = {

    assert(description.length <= LONG_STRING, "description too long")
    val descriptionOk = sanitizeHtml(description)

    validateIdentifier(layout, "invalid layout")
    assert(max >= 1, "invalid max")
    assert(max <= answers.size, "max greater than answers")
    assert(min >= 0, "invalid min")
    assert(min <= answers.size, "min greater than answers")
    assert(num_winners >= 1, "invalid num_winners")
    assert(num_winners <= answers.size, "num_winners greater than answers")

    // check maximum number of answers
    var maxNumAnswers = Play.current.configuration.getInt("election.limits.maxNumAnswers").getOrElse(10000)
    assert(
      answers.size <= maxNumAnswers,
      s"too many answers: answers.size(${answers.size}) > maxNumAnswers($maxNumAnswers)"
    )

    validateStringLength(title, LONG_STRING, s"title too large: $title")
    // TODO not looking inside the value
    validateIdentifier(tally_type, "invalid tally_type")
    // TODO not looking inside the value
    validateIdentifier(answer_total_votes_percentage, "invalid answer_total_votes_percentage")
    val answersOk = answers.map(_.validate())
    val repeatedAnswers =  answers
      .filter { x => answers.count(_.text == x.text) > 1 }
      .map { x => x.text }
    val repeatedAnswersStr = repeatedAnswers.toSet.mkString(", ")
    assert(repeatedAnswers.length == 0, s"answers texts repeated: $repeatedAnswersStr")

    // validate shuffle categories
    if (extra_options.isDefined && 
        extra_options.get.shuffle_category_list.isDefined &&
        extra_options.get.shuffle_category_list.get.size > 0) {
      val categories = answers.map{ x => x.category } toSet

      extra_options.get.shuffle_category_list.get.map { x =>
        assert(categories.contains(x), s"category $x in shuffle_category_list not found is invalid")
      }
    }

    this.copy(description = descriptionOk, answers = answersOk)
  }
}

/** defines question extra data in an election */
case class QuestionExtra(
  group: Option[String],
  next_button: Option[String],
  shuffled_categories: Option[String],
  shuffling_policy: Option[String],
  ballot_parity_criteria: Option[String],
  restrict_choices_by_tag__name: Option[String],
  restrict_choices_by_tag__max: Option[String],
  restrict_choices_by_tag__max_error_msg: Option[String],
  accordion_folding_policy: Option[String],
  restrict_choices_by_no_tag__max: Option[String],
  force_allow_blank_vote: Option[String],
  recommended_preset__tag: Option[String],
  recommended_preset__title: Option[String],
  recommended_preset__accept_text: Option[String],
  recommended_preset__deny_text: Option[String],
  shuffle_categories: Option[Boolean],
  shuffle_all_options: Option[Boolean],
  shuffle_category_list: Option[Array[String]],
  show_points: Option[Boolean],
  default_selected_option_ids: Option[Array[Int]],
  select_categories_1click: Option[Boolean],
  answer_columns_size: Option[Int],
  group_answer_pairs: Option[Boolean])
{

  def validate() = {
    assert(!group.isDefined || group.get.length <= SHORT_STRING, "group too long")
    assert(!next_button.isDefined || next_button.get.length <= SHORT_STRING, "next_button too long")
    assert(!shuffled_categories.isDefined || shuffled_categories.get.length <= LONG_STRING, "shuffled_categories too long")
    assert(!shuffling_policy.isDefined || shuffling_policy.get.length <= SHORT_STRING, "shuffling_policy too long")
    assert(!ballot_parity_criteria.isDefined || ballot_parity_criteria.get.length <= SHORT_STRING, "ballot_parity_criteria too long")

    assert(!restrict_choices_by_tag__name.isDefined || restrict_choices_by_tag__name.get.length <= SHORT_STRING, "restrict_choices_by_tag__name too long")
    assert(!restrict_choices_by_tag__max.isDefined || restrict_choices_by_tag__max.get.toInt >= 1, "invalid restrict_choices_by_tag__max")
    assert(!restrict_choices_by_no_tag__max.isDefined || restrict_choices_by_no_tag__max.get.toInt >= 1, "invalid restrict_choices_by_no_tag__max")
    assert(!restrict_choices_by_tag__max_error_msg.isDefined || restrict_choices_by_tag__max_error_msg.get.length <= SHORT_STRING, "shuffling_policy too long")


    assert(!force_allow_blank_vote.isDefined || force_allow_blank_vote.get.length <= SHORT_STRING, "force_allow_blank_vote too long")

    assert(!recommended_preset__tag.isDefined || recommended_preset__tag.get.length <= SHORT_STRING, "recommended_preset__tag too long")
    assert(!recommended_preset__title.isDefined || recommended_preset__title.get.length <= LONG_STRING, "recommended_preset__title too long")
    assert(!recommended_preset__accept_text.isDefined || recommended_preset__accept_text.get.length <= LONG_STRING, "recommended_preset__accept_text too long")
    assert(!recommended_preset__deny_text.isDefined || recommended_preset__deny_text.get.length <= LONG_STRING, "recommended_preset__deny_text too long")


    assert(!answer_columns_size.isDefined || List(12,6,4,3).contains(answer_columns_size.get), "invalid answer_columns_size, can only be a string with 12,6,4,3")

    assert(!(shuffle_all_options.isDefined && shuffle_category_list.isDefined &&
           shuffle_all_options.get) || 0 == shuffle_category_list.get.size,
           "shuffle_all_options is true but shuffle_category_list is not empty")
  }
}

/** Defines a possible list of conditions under which a question is shown */
case class ConditionalQuestion(
  question_id: Int,
  when_any: Array[QuestionCondition])
{
  def validate() =
  {
  }
}

case class QuestionCondition(
  question_id: Int,
  answer_id: Int)
{
  def validate() =
  {
  }
}

/** defines a possible answer for a question asked in an election */
case class Answer(id: Int, category: String, details: String, sort_order: Int, urls: Array[Url], text: String) {

  def validate() = {
    assert(id >= 0, "invalid id")
    validateStringLength(category, SHORT_STRING, s"category too large $category")

    assert(details.length <= LONG_STRING, "details too long")
    val detailsOk = sanitizeHtml(details)
    // TODO not looking inside the value
    assert(sort_order >= 0, "invalid sort_order")
    assert(text.length <= LONG_STRING, "text too long")
    val textOk = sanitizeHtml(text)
    val urlsOk = urls.map(_.validate())

    this.copy(details = detailsOk, urls = urlsOk, text = textOk)
  }
}

case class ShareTextItem(
  network: String,
  button_text: String,
  social_message: String
)
{
  def validate = {
    this
  }
}

/** defines presentation options for an election */
case class ElectionPresentation(
  share_text: Option[Array[ShareTextItem]],
  theme: String,
  urls: Array[Url],
  theme_css: String,
  extra_options: Option[ElectionExtra],
  show_login_link_on_home: Option[Boolean],
  conditional_questions: Option[Array[ConditionalQuestion]])
{
  def shareTextConfig() : Option[Array[ShareTextItem]]  = {
    val allow_edit: Boolean = Play.current.configuration.getBoolean("share_social.allow_edit").getOrElse(false)
    if (allow_edit) {
      share_text
    } else {
      Play.current.configuration.getConfigSeq("share_social.default") map { seq =>
        (seq map { item =>
            ShareTextItem ( item.getString("network").get, item.getString("button_text").get, item.getString("social_message").get )
        }).toArray
      }
    }
  }

  def validate() = {

    validateIdentifier(theme, "invalid theme")
    val urlsOk = urls.map(_.validate())
    validateIdentifier(theme_css, "invalid theme_css")
    val shareText = shareTextConfig()

    this.copy(urls = urlsOk, share_text = shareText)
  }
}

/** defines election presentation extra options for an election */
case class ElectionExtra(
  start_screen__skip: Option[Boolean],
  success_screen__hide_ballot_tracker: Option[Boolean],
  success_screen__redirect_to_login: Option[Boolean],
  success_screen__redirect_to_login__text: Option[String],
  success_screen__redirect_to_login__auto_seconds: Option[Int]
)

/** an url to be shown when presenting election data */
case class Url(title: String, url: String) {

  def validate() = {
    validateStringLength(title, SHORT_STRING, s"invalid url title $title")
    validateStringLength(url, SHORT_STRING, s"too long url $url")

    this
  }
}


/** eo create election response message */
case class CreateResponse(status: String, session_data: Array[PublicKeySession])

/** eo public key message component */
case class PublicKeySession(pubkey: PublicKey, session_id: String)

/** el gamal public key */
case class PublicKey(q: BigInt, p: BigInt, y:BigInt, g: BigInt)

/** eo tally election response message */
case class TallyResponse(status: String, data: TallyData)

/** eo tally data message component */
case class TallyData(tally_url: String, tally_hash: String)


/** json vote submitted to the ballot box, when validated becomes a Vote */
case class VoteDTO(vote: String, vote_hash: String) {
  def validate(pks: Array[PublicKey], checkResidues: Boolean, electionId: Long, voterId: String) = {
    val json = Json.parse(vote)
    val encryptedValue = json.validate[EncryptedVote]

    encryptedValue.fold (
      errors => throw new ValidationException(s"Error parsing vote json: $errors"),
      encrypted => {

        encrypted.validate(pks, checkResidues)

        val hashed = Crypto.sha256(vote)

        if(hashed != vote_hash) throw new ValidationException("Hash mismatch")

        Vote(None, electionId, voterId, vote, vote_hash, new Timestamp(new Date().getTime))
      }
    )
  }
}

/** the ciphertext present in a json vote (VoteDTO), including proofs of plaintext knowledge */
case class EncryptedVote(choices: Array[Choice], issue_date: String, proofs: Array[Popk]) {

  /** ciphertext validation: choice is quadratic residue and validation of proof of plaintext knowledge */
  def validate(pks: Array[PublicKey], checkResidues: Boolean) = {

    if(checkResidues) {
      choices.zipWithIndex.foreach { case (choice, index) =>
        choice.validate(pks(index))
      }
    }

    checkPopk(pks)
  }

  /** validates proof of plaintext knowledge, schnorr protocol */
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

/** the el-gamal ciphertext itself */
case class Choice(alpha: BigInt, beta: BigInt) {

  /** checks that both alpha and beta are quadratic residues in p */
  def validate(pk: PublicKey) = {

    if(!Crypto.quadraticResidue(alpha, pk.p)) throw new ValidationException("Alpha quadratic non-residue")
    if(!Crypto.quadraticResidue(beta, pk.p)) throw new ValidationException("Beta quadratic non-residue")
  }
}

/** proof of plaintext knowledge, according to schnorr protocol*/
case class Popk(challenge: BigInt, commitment: BigInt, response: BigInt)

/** data describing an authority, used in admin interface */
case class AuthData(name: Option[String], description: Option[String], url: Option[String], image: Option[String])

case class PlaintextAnswer(options: Array[Long] = Array[Long]())
// id is the election ID
case class PlaintextBallot(id: Long = -1, answers: Array[PlaintextAnswer] = Array[PlaintextAnswer]())

case class Callback(name: String, payload: String)

/**
 * This object contains the states required for reading a plaintext ballot
 * It's used on Console.processPlaintextLine
 */
object PlaintextBallot
{
  val ID = 0 // reading election ID
  val ANSWER = 1 // reading answers
}
