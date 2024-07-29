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

import utils.Crypto
import utils.JsonFormatters._
import utils.Validator._
import utils.Validator
import utils.ValidationException

import play.api.Play
import play.api.cache.Cache
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

  def checkHash(electionId: Long, hash: String)(implicit s: Session): Option[Vote] = 
  {
    val election = Elections.findById(electionId).get
    val electionConfigStr = Json.parse(election.configuration).as[JsObject]
    val electionConfig = electionConfigStr.validate[ElectionConfig]
    electionConfig.fold(
      errors => None,
      configJson =>
      {
        val virtualSubElections = configJson.virtualSubelections.get
        val electionIds = List.concat(virtualSubElections, List(electionId))
        val vote = votes
          .filter(_.electionId.inSet(electionIds))
          .filter(_.hash === hash)
          .firstOption

        // we make sure the hash corresponds to the last vote, otherwise return None
        vote.flatMap { v =>
          val latest = votes
            .filter(_.electionId === vote.get.election_id)
            .filter(_.voterId === v.voter_id)
            .sortBy(_.created.desc)
            .firstOption
          latest.filter(_.hash == hash)
        }
      }
    )
  }

  // def count(implicit s: Session): Int = Query(votes.length).first
  def count(implicit s: Session): Int = votes.length.run

  def countForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).length.run
  def countUniqueForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).groupBy(v=>v.voterId).map(_._1).length.run

  def countForElectionAndVoter(electionId: Long, voterId: String)(implicit s: Session): Int = {
    votes.filter(_.electionId === electionId).filter(_.voterId === voterId).length.run
  }
}

/** trustee key state object */
case class TrusteeKeyState (
  id: String,
  state: String
)

object TrusteeKeysStates {
  val INITIAL = "initial"
  val DOWNLOADED = "downloaded"
  val DELETED = "deleted"
  val RESTORED = "restored"
}

/** election object */
case class Election(
  id: Long,
  configuration: String,
  state: String,
  tally_state: String,
  startDate: Option[Timestamp],
  endDate: Option[Timestamp],
  pks: Option[String],
  tallyPipesConfig: Option[String],
  ballotBoxesResultsConfig: Option[String],
  results: Option[String],
  resultsUpdated: Option[Timestamp],
  publishedResults: Option[String],
  virtual: Boolean,
  tallyAllowed: Boolean,
  publicCandidates: Boolean,
  logo_url: Option[String],
  trusteeKeysState: Option[String],
  //segmentedMixing: Option[Boolean],
  weightedVotingField: Option[String]
)
{

  def getDTO(showCandidates: Boolean = true) =
  {
    var configJson = Json.parse(configuration)
    if (!configJson.as[JsObject].keys.contains("layout")) {
        configJson = configJson.as[JsObject] + ("layout" -> Json.toJson("simple"))
    }

    if (!configJson.as[JsObject].keys.contains("virtual")) {
        configJson = configJson.as[JsObject] + ("virtual" -> Json.toJson(virtual))
    }

    if (!configJson.as[JsObject].keys.contains("tallyPipesConfig")) {
        configJson = configJson.as[JsObject] + ("tallyPipesConfig" -> Json.toJson(tallyPipesConfig))
    }

    if (!configJson.as[JsObject].keys.contains("ballotBoxesResultsConfig")) {
        configJson = configJson.as[JsObject] + ("ballotBoxesResultsConfig" -> Json.toJson(ballotBoxesResultsConfig))
    }

    if (!configJson.as[JsObject].keys.contains("logo_url")) {
        configJson = configJson.as[JsObject] + ("logo_url" -> Json.toJson(logo_url))
    }

    if (!configJson.as[JsObject].keys.contains("tally_allowed")) {
        configJson = configJson.as[JsObject] + ("tally_allowed" -> Json.toJson(tallyAllowed))
    }

    if (!configJson.as[JsObject].keys.contains("publicCandidates")) {
        configJson = configJson.as[JsObject] + ("publicCandidates" -> Json.toJson(publicCandidates))
    }

    //if (!configJson.as[JsObject].keys.contains("segmentedMixing")) {
    //    configJson = configJson.as[JsObject] + ("segmentedMixing" -> Json.toJson(segmentedMixing))
    //}

    if (!configJson.as[JsObject].keys.contains("weightedVotingField")) {
        configJson = configJson.as[JsObject] + ("weightedVotingField" -> Json.toJson(weightedVotingField))
    }

    var config = configJson.validate[ElectionConfig].get
    var privacyConfig = (showCandidates) match {
      case true => config
      case false => config.copy(
          questions=config.questions.map(
            (question) => {
              question.copy(answers=Array())
            }
          )
        )
    }
    var resUp = None: Option[Timestamp]

    if (state == Elections.RESULTS_PUB) {
        resUp = resultsUpdated
    }

    val trusteeKeysStateJson = Json.parse(trusteeKeysState.getOrElse("[]"))
    val trusteeKeysStateParsed = trusteeKeysStateJson.validate[Array[TrusteeKeyState]].get

    ElectionDTO(
      id,
      privacyConfig,
      state,
      tally_state,
      startDate,
      endDate,
      pks,
      tallyPipesConfig,
      ballotBoxesResultsConfig,
      publishedResults,
      resUp,
      virtual,
      tallyAllowed,
      publicCandidates,
      logo_url,
      trusteeKeysStateParsed,
      //segmentedMixing,
      weightedVotingField
    )
  }
}

/** relational representation of elections */
class Elections(tag: Tag)
  extends Table[Election](tag, "election")
{
  def id = column[Long]("id", O.PrimaryKey)
  def configuration = column[String]("configuration", O.NotNull, O.DBType("text"))
  def state = column[String]("state", O.NotNull)
  def tally_state = column[String]("tally_state", O.NotNull)
  def startDate = column[Timestamp]("start_date", O.Nullable)
  def endDate = column[Timestamp]("end_date", O.Nullable)
  def pks = column[String]("pks", O.Nullable, O.DBType("text"))
  def tallyPipesConfig = column[String]("results_config", O.Nullable, O.DBType("text"))
  def ballotBoxesResultsConfig = column[String]("ballot_boxes_results_config", O.Nullable, O.DBType("text"))
  def results = column[String]("results", O.Nullable, O.DBType("text"))
  def resultsUpdated = column[Timestamp]("results_updated", O.Nullable)
  def publishedResults = column[String]("published_results", O.Nullable, O.DBType("text"))
  def virtual = column[Boolean]("virtual")
  def tallyAllowed = column[Boolean]("tally_allowed")
  def publicCandidates = column[Boolean]("public_candidates")
  //def segmentedMixing = column[Boolean]("segmented_mixing", O.Nullable)
  def weightedVotingField = column[String]("weighted_voting_field", O.Nullable, O.DBType("text"))
  def logo_url = column[String]("logo_url", O.Nullable, O.DBType("text"))
  def trusteeKeysState = column[String]("trustee_keys_state", O.Nullable, O.DBType("text"))

  def * = (
    id,
    configuration,
    state,
    tally_state,
    startDate.?,
    endDate.?,
    pks.?,
    tallyPipesConfig.?,
    ballotBoxesResultsConfig.?,
    results.?,
    resultsUpdated.?,
    publishedResults.?,
    virtual,
    tallyAllowed,
    publicCandidates,
    logo_url.?,
    trusteeKeysState.?,
    //segmentedMixing.?,
    weightedVotingField.?

  ) <> (Election.tupled, Election.unapply _)
}

/** data access object for elections */
object Elections {
  val REGISTERED = "registered"
  val CREATED = "created"
  val CREATE_ERROR = "create_error"
  val STARTED = "started"
  val SUSPENDED = "suspended"
  val RESUMED = "resumed"
  val STOPPED = "stopped"
  val NO_TALLY = "no_tally"
  val DOING_TALLY = "doing_tally"
  val TALLY_OK = "tally_ok"
  val TALLY_ERROR = "tally_error"
  val RESULTS_OK = "results_ok"
  val RESULTS_PUB = "results_pub"

  val elections = TableQuery[Elections]

  def findById(id: Long)(implicit s: Session): Option[Election] = 
    elections.filter(_.id === id).firstOption

  def count(implicit s: Session): Int = elections.length.run

  def insert(election: Election)(implicit s: Session) = {
    (elections returning elections.map(_.id)) += election
  }

  def update(theId: Long, election: Election)(implicit s: Session) = {
    val electionToWrite = election.copy(id = theId)
    elections.filter(_.id === theId).update(electionToWrite)
  }

  def updateState(id: Long, current_state: String, state: String)
  (implicit s: Session): Int = 
  {
    if (state == current_state) {
      return 0
    }
    val enforceStateControls = Play
      .current
      .configuration
      .getBoolean("elections.enforceStateControls")
      .getOrElse(true)
    
    // enforce only allowed state transitions happen
    if (enforceStateControls) {
      if (
        (state == CREATED && current_state != REGISTERED) ||
        (state == CREATE_ERROR && current_state != REGISTERED) ||
        (state == STARTED && current_state != CREATED) ||
        (
          state == SUSPENDED &&
          current_state != STARTED &&
          current_state != RESUMED
        ) ||
        (state == RESUMED && current_state != SUSPENDED) ||
        (
          state == STOPPED &&
          current_state != STARTED &&
          current_state != SUSPENDED &&
          current_state != RESUMED &&
          current_state != TALLY_ERROR
        ) ||
        (
          state == DOING_TALLY &&
          current_state != STOPPED &&
          current_state != DOING_TALLY
        ) ||
        (
          state == TALLY_OK &&
          current_state != DOING_TALLY &&
          current_state != STOPPED // This is allowed for virtual elections
        ) ||
        (
          state == TALLY_ERROR &&
          current_state != DOING_TALLY &&
          current_state != STOPPED
        ) ||
        (
          state == RESULTS_OK &&
          current_state != TALLY_OK &&
          current_state != RESULTS_OK &&
          current_state != RESULTS_PUB
        ) ||
        (
          state == RESULTS_PUB &&
          current_state != RESULTS_PUB &&
          current_state != RESULTS_OK
        )
      ) {
        throw new Exception(
          s"Invalid state=${current_state} to change to ${state}"
        )
      }
    }
    state match {
      case STARTED => 
        elections
          .filter(_.id === id)
          .map(e => (e.state, e.startDate))
          .update(
            state, 
            new Timestamp(new Date().getTime)
          )
      case STOPPED => 
        elections
          .filter(_.id === id)
          .map(e => (e.state, e.endDate))
          .update(
            state, 
            new Timestamp(new Date().getTime)
          )
      case TALLY_OK =>
        elections
          .filter(_.id === id)
          .map(e => (e.state, e.tally_state))
          .update(
            state, 
            state
          )
      case DOING_TALLY =>
        elections
          .filter(_.id === id)
          .map(e => (e.state, e.tally_state))
          .update(
            state, 
            state
          )
      case TALLY_ERROR =>
        elections
          .filter(_.id === id)
          .map(e => (e.tally_state))
          .update(
            state
          )
      case RESULTS_OK =>
        elections
          .filter(_.id === id)
          .map(e => (e.state, e.tally_state))
          .update(
            state, 
            state
          )
      case _ => 
        elections
          .filter(_.id === id)
          .map(e => e.state)
          .update(state)
    }
  }

  def allowTally(id: Long)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.tallyAllowed).update(true)
  }

  def setPublicCandidates(
    id: Long,
    publicCandidates: Boolean
  ) (
    implicit s: Session
  ) = {
    elections
      .filter(_.id === id)
      .map(e => e.publicCandidates)
      .update(publicCandidates)
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

  def updatePublishedResults(id: Long, results: String)(implicit s: Session) = {
    elections
      .filter(_.id === id)
      .map(e => e.publishedResults)
      .update(results)
  }

  def updateResults(id: Long, results: String, updateStatus: Boolean)(implicit s: Session) = {
    if (updateStatus) {
      elections
        .filter(_.id === id)
        .map(e => (e.state, e.results, e.resultsUpdated))
        .update(
          Elections.RESULTS_OK,
          results,
          new Timestamp(new Date().getTime)
        )
    } else {
      elections
        .filter(_.id === id)
        .map(e => (e.results, e.resultsUpdated))
        .update(
          results,
          new Timestamp(new Date().getTime)
        )
    }
  }

  def updateTallyState(id: Long, state: String)(implicit s: Session) = {
      elections
        .filter(_.id === id)
        .map(e => (e.tally_state))
        .update(
          state
        )
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
  def updateResultsConfig(id: Long, tallyPipesConfig: String)
  (implicit s: Session) =
  {
    elections
      .filter(_.id === id)
      .map(e => (e.tallyPipesConfig))
      .update(tallyPipesConfig)
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
case class PublicCandidatesDTO(
  publicCandidates: Boolean
)

/** used to return an election with config in structured form */
case class ElectionDTO(
  id: Long,
  configuration: ElectionConfig,
  state: String,
  tally_state: String,
  startDate: Option[Timestamp],
  endDate: Option[Timestamp],
  pks: Option[String],
  tallyPipesConfig: Option[String],
  ballotBoxesResultsConfig: Option[String],
  results: Option[String],
  resultsUpdated: Option[Timestamp],
  virtual: Boolean,
  tallyAllowed: Boolean,
  publicCandidates: Boolean,
  logo_url: Option[String],
  trusteeKeysState: Array[TrusteeKeyState],
  //segmentedMixing: Option[Boolean],
  weightedVotingField: Option[String]
)

/*case class MixingCategorySegmentation(
  categoryName: String,
  categories: Array[String]
)
{
  def validate() =
  {
    assert(
      categoryName.length <= SHORT_STRING && categoryName.length > 0,
      s"invalid mixing category name '${categoryName}'"
    )
    categories.foreach { category =>
      assert(
        category.length <= SHORT_STRING && category.length > 0, 
        s"Invalid category name '${category}'"
      )
    }
    assert(
        categories.size == categories.toSet.size,
        s"repeated categories in: ${categories}"
      )
  }
}*/

/** an election configuration defines an election */
case class ElectionConfig(
  id: Long,
  layout: String,
  director: String,
  authorities: Array[String],
  title: String,
  title_i18n: Option[Map[String, String]],
  description: String,
  description_i18n: Option[Map[String, String]],
  questions: Array[Question],
  start_date: Option[Timestamp],
  end_date: Option[Timestamp],
  presentation: ElectionPresentation,
  extra_data: Option[String],
  tallyPipesConfig: Option[String],
  ballotBoxesResultsConfig: Option[String],
  virtual: Boolean,
  tally_allowed: Boolean,
  publicCandidates: Boolean,
  //segmentedMixing: Option[Boolean],
  weightedVotingField: Option[String],
  virtualSubelections: Option[Array[Long]],
  //mixingCategorySegmentation: Option[MixingCategorySegmentation],
  logo_url: Option[String])
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
      assert(
        peers.contains(auth), 
        s"Authority '${auth}' not found in peers: ${peers.keys}"
      )
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

    /*assert(
      (
        !mixingCategorySegmentation.isDefined ||
        (segmentedMixing.isDefined && segmentedMixing.get)
      ),
      "segmentedMixing needs to be enabled if mixingCategorySegmentation is set"
    )*/

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

    this.copy(questions = questionsOk, presentation = presentationOk)
  }

  /** returns a json string representation */
  def asString() = {
    Json.stringify(Json.toJson(this))
  }
}

/** defines a question asked in an election */
case class Question(
  description: String,
  description_i18n: Option[Map[String, String]],
  layout: String,
  max: Int, 
  min: Int, 
  num_winners: Int, 
  title: String,
  title_i18n: Option[Map[String, String]],
  tally_type: String, 
  answer_total_votes_percentage: String, 
  answers: Array[Answer], 
  extra_options: Option[QuestionExtra]
) {

  def validate() = {
    assert(description.length <= LONG_STRING, "description too long")
    val descriptionOk = sanitizeHtml(description)
    val cumulative = 
      if (
        extra_options.isDefined && 
        extra_options.get.cumulative_number_of_checkboxes.isDefined
      ) 
      /*then */ extra_options.get.cumulative_number_of_checkboxes.get
      else 1;

    validateIdentifier(layout, "invalid layout")
    assert(max >= 1, "invalid max")
    assert(max <= answers.size * cumulative, "max greater than checkable answers")
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
      // do not include in the count write-ins, as the text there is empty
      .filter { answer => 
        answer.urls.filter { 
          url => (url.url == "true" && url.title == "isWriteIn")
        }.length == 0
      }
      .filter { x => answers.count(_.text == x.text) > 1 }
      .map { x => x.text }
    val repeatedAnswersStr = repeatedAnswers.toSet.mkString(", ")
    assert(repeatedAnswers.length == 0, s"answers texts repeated: $repeatedAnswersStr")

    // validate answer ids
    val answerIds = answers
      .map { answer => answer.id }
      .toSet
    val shouldAnswerIds = answers
      .zipWithIndex
      .map { case (_answer, index) => index }
      .toSet
    assert(
      answerIds == shouldAnswerIds,
      "answer ids should start with 0 and have no missing number in between"
    )

    if (extra_options.isDefined) {
      extra_options.get.validate()
    }

    // validate shuffle categories
    if (
      extra_options.isDefined &&
      extra_options.get.shuffle_category_list.isDefined &&
      extra_options.get.shuffle_category_list.get.size > 0
    ) {
      val categories = answers.map{ x => x.category } toSet

      extra_options.get.shuffle_category_list.get.map { x =>
        assert(categories.contains(x), s"category $x in shuffle_category_list not found is invalid")
      }
    }

    // validate cumulative layout
    if (tally_type == "cumulative") {
      // ensure extra option is defined
      assert(
        extra_options.isDefined && 
        extra_options.get.cumulative_number_of_checkboxes.isDefined && 
        extra_options.get.cumulative_number_of_checkboxes.get >= 1,
        "cumulative_number_of_checkboxes must be >= 1"
      )
      // only supported in simultaneous-questions layout!
      assert(
        layout == "simultaneous-questions" || layout == "simultaneous-questions-v2",
        "cumulative tally type is only supported in simultaneous-questions"
      )
    }
    
    assert(
      (
        !extra_options.isDefined ||
        !extra_options.get.enable_checkable_lists.isDefined ||
        List(
          "disabled",
          "allow-selecting-candidates-and-lists",
          "allow-selecting-candidates",
          "allow-selecting-lists"
        ).contains(extra_options.get.enable_checkable_lists.get)
      ),
      "enable_checkable_lists must be one of 'disabled', 'allow-selecting-candidates-and-lists', 'allow-selecting-candidates', 'allow-selecting-lists'"
    )

    // if enable_checkable_lists is set, verify that for each category there
    // is an list answer
    if (
      extra_options.isDefined &&
      extra_options.get.enable_checkable_lists.isDefined
    ) {
      // getting category names from answers
      val answerCategoryNames = answers
        .filter { answer => 
          answer.category.length > 0 &&
          answer.urls.filter {
            url => (url.url == "true" && url.title == "isCategoryList")
          }.length == 0
        }
        .map { answer => answer.category }
        .toSet
      // getting category answers
      val categoryNames = answers
        .filter { answer => 
          answer.urls.filter {
            url => (url.url == "true" && url.title == "isCategoryList")
          }.length > 0
        }
        .map { answer => answer.text }
        .toSet
      assert(
        categoryNames == answerCategoryNames,
        s"there needs to be one isCategoryList answer for each category when enable_checkable_lists is not 'disabled'"
      )
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
  answer_group_columns_size: Option[Int],
  select_all_category_clicks: Option[Int],
  enable_panachage: Option[Boolean], // default = true
  cumulative_number_of_checkboxes: Option[Int], // default = 1
  enable_checkable_lists: Option[String], // default = "disabled"
  allow_writeins: Option[Boolean], // default = false
  invalid_vote_policy: Option[String], // allowed, warn, not-allowed, warn-invalid-implicit-and-explicit
  review_screen__show_question_description: Option[Boolean], // default = false
  write_in_config: Option[WriteInConfig],
  show_filter_field: Option[Boolean] // default = false
 )
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

    assert(!answer_group_columns_size.isDefined || List(12,6,4,3).contains(answer_group_columns_size.get), "invalid answer_group_columns_size, can only be a string with 12,6,4,3")

    assert(!(shuffle_all_options.isDefined && shuffle_category_list.isDefined &&
           shuffle_all_options.get) || 0 == shuffle_category_list.get.size,
           "shuffle_all_options is true but shuffle_category_list is not empty")

    assert(!select_all_category_clicks.isDefined || select_all_category_clicks.get >= 1, "select_all_category_clicks must be >= 1")

    assert(!cumulative_number_of_checkboxes.isDefined || cumulative_number_of_checkboxes.get.toInt >= 1, "cumulative_number_of_checkboxes must be >= 1")

    assert(
      (
        !invalid_vote_policy.isDefined || 
        List("allowed", "warn", "not-allowed", "warn-invalid-implicit-and-explicit").contains(invalid_vote_policy.get)
      ),
      "invalid_vote_policy must be 'allowed', 'warn', 'warn-invalid-implicit-and-explicit' or 'not-allowed'"
    )
  }
}

/** Defines the configuration (ie extra fields) for write ins */
case class WriteInConfig (
  fields: Array[WriteInField],
  template: String)
{
  def validate = {
    this
  }
}

/** Defines extra fields for write ins */
case class WriteInField (
  id: String,  // name of the field
  placeholder: String, // input text placeholder
  placeholder_i18n: Option[Map[String, String]],
  label: Option[String], // input text label
  label_i18n: Option[Map[String, String]],
  help: Option[String],
  help_i18n: Option[Map[String, String]],
  min: Int, // minimum 0
  max: Int) // negative if there's no max
{
  def validate = {
    assert(id.length > 1, "id too short")
    assert(id.length <= LONG_STRING, "id too long")
    assert(min >= 1, "min too small")
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
case class Answer(
  id: Int,
  category: String,
  details: String,
  details_i18n: Option[Map[String, String]],
  sort_order: Int,
  urls: Array[Url],
  text: String,
  text_i18n: Option[Map[String, String]]
) {
  def validate() = {
    assert(id >= 0, "invalid id")
    validateStringLength(
      category, 
      SHORT_STRING, 
      s"category too large $category"
    )

    assert(details.length <= LONG_STRING, "details too long")
    val detailsOk = sanitizeHtml(details)
    // TODO not looking inside the value
    assert(sort_order >= 0, "invalid sort_order")
    
    val isWriteIn = urls.filter {
      url => (url.url == "true" && url.title == "isWriteIn")
    }.length != 0
    assert(isWriteIn || text.length > 0, "text too short")
    assert(isWriteIn || text.length <= LONG_STRING, "text too long")
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

case class VoterEligibilityScreen(
  title: Option[String],
  title_i18n: Option[Map[String, String]],
  description: Option[String],
  description_i18n: Option[Map[String, String]],
  footer: Option[String],
  footer_i18n: Option[Map[String, String]]
)
{
  def validate() =
  {
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
  election_board_ceremony: Option[Boolean], // default = false
  conditional_questions: Option[Array[ConditionalQuestion]],
  pdf_url: Option[Url],
  i18n_languages_conf: Option[LanguagesConf],
  anchor_continue_btn_to_bottom: Option[Boolean],
  booth_log_out__countdown_seconds: Option[Int],
  mandatory_acceptance_tos_html: Option[String],
  mandatory_acceptance_tos_html_i18n: Option[Map[String, String]],

  // Override translations for languages. Example:
  // {"en": {"avRegistration.forgotPassword": "Whatever"}}
  i18n_override: Option[Map[String, Map[String, String]]],

  public_title: Option[String],
  public_title_i18n: Option[Map[String, String]],
  voter_eligibility_screen: Option[VoterEligibilityScreen]
)
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
    assert(theme_css.length <= LONG_STRING, "theme_css too long")
    val shareText = shareTextConfig()

    this.copy(urls = urlsOk, share_text = shareText)
  }
}

/** Language behavior configuration */
case class LanguagesConf(
  default_language: String,
  force_default_language: Boolean,
  available_languages: List[String]
)

/** defines election presentation extra options for an election */
case class ElectionExtra(
  allow_voting_end_graceful_period: Option[Boolean],
  start_screen__skip: Option[Boolean],
  booth_log_out__disable: Option[Boolean],
  disable__demo_voting_booth: Option[Boolean],
  disable__public_home: Option[Boolean],
  disable_voting_booth_audit_ballot: Option[Boolean],
  disable__election_chooser_screen: Option[Boolean],
  success_screen__hide_ballot_tracker: Option[Boolean],
  success_screen__hide_qr_code: Option[Boolean],
  success_screen__hide_download_ballot_ticket: Option[Boolean],
  success_screen__redirect__url: Option[String],
  success_screen__redirect_to_login: Option[Boolean],
  success_screen__redirect_to_login__text: Option[String],
  success_screen__redirect_to_login__auto_seconds: Option[Int],
  success_screen__ballot_ticket__logo_url: Option[String],
  success_screen__ballot_ticket__logo_header: Option[String],
  success_screen__ballot_ticket__logo_subheader: Option[String],
  success_screen__ballot_ticket__h3: Option[String],
  success_screen__ballot_ticket__h4: Option[String],
  success_screen__ballot_ticket__show_election_id: Option[Boolean],
  review_screen__split_cast_edit: Option[Boolean],
  show_skip_question_button: Option[Boolean]
) {
  def validate() = {
    if (success_screen__redirect_to_login__text.isDefined) 
    {
      validateStringLength(success_screen__redirect_to_login__text.get, SHORT_STRING, s"too long success_screen__redirect_to_login__text ${success_screen__redirect_to_login__text.get}")
    }
    if (success_screen__redirect__url.isDefined)
    {
      validateStringLength(success_screen__redirect__url.get, SHORT_STRING, s"too long success_screen__redirect__url ${success_screen__redirect__url.get}")
    }
    if (success_screen__ballot_ticket__logo_url.isDefined) 
    {
      validateStringLength(success_screen__ballot_ticket__logo_url.get, SHORT_STRING, s"too long success_screen__ballot_ticket__logo_url ${success_screen__ballot_ticket__logo_url.get}")
    }
    if (success_screen__ballot_ticket__logo_header.isDefined) 
    {
      validateStringLength(success_screen__ballot_ticket__logo_header.get, SHORT_STRING, s"too long success_screen__ballot_ticket__logo_header ${success_screen__ballot_ticket__logo_header.get}")
    }
    if (success_screen__ballot_ticket__logo_subheader.isDefined) 
    {
      validateStringLength(success_screen__ballot_ticket__logo_subheader.get, SHORT_STRING, s"too long success_screen__ballot_ticket__logo_subheader ${success_screen__ballot_ticket__logo_subheader.get}")
    }
    if (success_screen__ballot_ticket__h3.isDefined) 
    {
      validateStringLength(success_screen__ballot_ticket__h3.get, SHORT_STRING, s"too long success_screen__ballot_ticket__h3 ${success_screen__ballot_ticket__h3.get}")
    }
    if (success_screen__ballot_ticket__h4.isDefined) 
    {
      validateStringLength(success_screen__ballot_ticket__h4.get, SHORT_STRING, s"too long success_screen__ballot_ticket__h4 ${success_screen__ballot_ticket__h4.get}")
    }
    this
  }
}


/** an url to be shown when presenting election data */
case class Url(title: String, url: String) {

  def validate() = {
    validateStringLength(title, SHORT_STRING, s"invalid url title $title")
    validateStringLength(url, SHORT_STRING, s"too long url $url")

    this
  }
}

/** */
case class DownloadPrivateKeyShareRequest(authority_id: String, username: String, password: String)

/** */
case class CheckPrivateKeyShareRequest(authority_id: String, username: String, password: String, private_key_base64: String)

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
