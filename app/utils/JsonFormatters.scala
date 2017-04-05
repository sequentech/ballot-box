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
package utils

import play.api.libs.json._
import play.api.libs.functional.syntax._
import models._
import java.sql.Timestamp
import scala.math.BigInt

/**
  * Formatters for json parsing and writing
  *
  */
object JsonFormatters {
  val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")

  implicit val formatTimestamp = new Format[Timestamp] {
    def writes(ts: Timestamp): JsValue = {
      JsString(dateFormat.format(ts))
    }
    def reads(ts: JsValue): JsResult[Timestamp] = {
      try {
        val date = dateFormat.parse(ts.as[String])
        JsSuccess(new Timestamp(date.getTime))
      } catch {
        case e: IllegalArgumentException => JsError("Unable to parse timestamp")
      }
    }
  }

  implicit val formatBigInt = new Format[BigInt] {
    def writes(bi: BigInt): JsValue = JsString(bi.toString())
    def reads(bi: JsValue): JsResult[BigInt] = {
      try {
        JsSuccess(BigInt(bi.as[String]))
      } catch {
        case e: IllegalArgumentException => JsError("Unable to parse BigInt")
      }
    }
  }

  implicit val voteDtoF = Json.format[VoteDTO]
  implicit val voteF = Json.format[Vote]
  implicit val electionExtraF = Json.format[ElectionExtra]
  implicit val questionConditionF = Json.format[QuestionCondition]
  implicit val conditionalQuestionF = Json.format[ConditionalQuestion]
  implicit val electionF = Json.format[Election]

  implicit val urlF = Json.format[Url]
  implicit val answerF = Json.format[Answer]

  //////////////////////////////////////////////////////////////////////////////
  // this is not pretty but at least it works
  // it's necessary for case classes with >= 22 fields 
  // for this version of Play (2.3.6)
  // implicit val questionExtraF = Json.format[QuestionExtra]
  // See http://stackoverflow.com/questions/28167971/scala-case-having-22-fields-but-having-issue-with-play-json-in-scala-2-11-5
  val questionExtraFirstF
    : 
      OFormat[(
        Option[String], Option[String], Option[String], Option[String],
        Option[String], Option[String], Option[String], Option[String],
        Option[String], Option[String], Option[String], Option[String],
        Option[String], Option[String], Option[String])
      ] =
    (
      (__ \ "group").format[Option[String]] and
      (__ \ "next_button").format[Option[String]] and
      (__ \ "shuffled_categories").format[Option[String]] and
      (__ \ "shuffling_policy").format[Option[String]] and
      (__ \ "ballot_parity_criteria").format[Option[String]] and
      (__ \ "restrict_choices_by_tag__name").format[Option[String]] and
      (__ \ "restrict_choices_by_tag__max").format[Option[String]] and
      (__ \ "restrict_choices_by_tag__max_error_msg").format[Option[String]] and
      (__ \ "accordion_folding_policy").format[Option[String]] and
      (__ \ "restrict_choices_by_no_tag__max").format[Option[String]] and
      (__ \ "force_allow_blank_vote").format[Option[String]] and
      (__ \ "recommended_preset__tag").format[Option[String]] and
      (__ \ "recommended_preset__title").format[Option[String]] and
      (__ \ "recommended_preset__accept_text").format[Option[String]] and
      (__ \ "recommended_preset__deny_text").format[Option[String]]
    ).tupled

  val questionExtraSecondF
    :
      OFormat[(
        Option[Boolean], Option[Boolean], Option[Array[String]],
        Option[Boolean], Option[Array[Int]], Option[Boolean], Option[String],
        Option[String])
      ] =
    (
      (__ \ "shuffle_categories").format[Option[Boolean]] and
      (__ \ "shuffle_all_options").format[Option[Boolean]] and
      (__ \ "shuffle_category_list").format[Option[Array[String]]] and
      (__ \ "show_points").format[Option[Boolean]] and
      (__ \ "default_selected_option_ids").format[Option[Array[Int]]] and
      (__ \ "select_categories_1click").format[Option[Boolean]] and
      (__ \ "answer_columns_size").format[Option[String]] and
      (__ \ "group_answer_pairs").format[Option[String]]
    ).tupled

  implicit val questionExtraF : Format[QuestionExtra] =
  ( questionExtraFirstF and questionExtraSecondF ).apply(
    {
      case (
        (
          group, next_button, shuffled_categories, shuffling_policy,
          ballot_parity_criteria, restrict_choices_by_tag__name,
          restrict_choices_by_tag__max, restrict_choices_by_tag__max_error_msg,
          accordion_folding_policy, restrict_choices_by_no_tag__max,
          force_allow_blank_vote, recommended_preset__tag,
          recommended_preset__title, recommended_preset__accept_text,
          recommended_preset__deny_text
        ),
        (
          shuffle_categories, shuffle_all_options, shuffle_category_list,
          show_points, default_selected_option_ids, select_categories_1click,
          answer_columns_size, group_answer_pairs
        )
      ) =>
        QuestionExtra(
          group, next_button, shuffled_categories, shuffling_policy,
          ballot_parity_criteria, restrict_choices_by_tag__name,
          restrict_choices_by_tag__max, restrict_choices_by_tag__max_error_msg,
          accordion_folding_policy, restrict_choices_by_no_tag__max,
          force_allow_blank_vote, recommended_preset__tag,
          recommended_preset__title, recommended_preset__accept_text,
          recommended_preset__deny_text,
          shuffle_categories, shuffle_all_options, shuffle_category_list,
          show_points, default_selected_option_ids, select_categories_1click,
          answer_columns_size, group_answer_pairs
        )
    },
    q
    =>
      (
        (
          q.group, q.next_button, q.shuffled_categories, q.shuffling_policy,
          q.ballot_parity_criteria, q.restrict_choices_by_tag__name,
          q.restrict_choices_by_tag__max, q.restrict_choices_by_tag__max_error_msg,
          q.accordion_folding_policy, q.restrict_choices_by_no_tag__max,
          q.force_allow_blank_vote, q.recommended_preset__tag,
          q.recommended_preset__title, q.recommended_preset__accept_text,
          q.recommended_preset__deny_text
        ),
        (
          q.shuffle_categories, q.shuffle_all_options, q.shuffle_category_list,
          q.show_points, q.default_selected_option_ids, q.select_categories_1click,
          q.answer_columns_size, q.group_answer_pairs
        )
      )
  )

  //////////////////////////////////////////////////////////////////////////////


  implicit val questionF = Json.format[Question]
  implicit val ShareTextItemF = Json.format[ShareTextItem]

  implicit val presentationF = Json.format[ElectionPresentation]
  implicit val configF = Json.format[ElectionConfig]
  implicit val statDayF = Json.format[StatDay]
  implicit val statsF = Json.format[Stats]
  implicit val electionDtoF = Json.format[ElectionDTO]

  implicit val publicKeyF = Json.format[PublicKey]
  implicit val publicKeySessionF = Json.format[PublicKeySession]
  implicit val createResponseF = Json.format[CreateResponse]

  implicit val popkF = Json.format[Popk]
  implicit val choiceF = Json.format[Choice]
  implicit val encryptedVoteF = Json.format[EncryptedVote]

  implicit val tallyDataF = Json.format[TallyData]
  implicit val tallyResponseF = Json.format[TallyResponse]

  implicit val authDataF = Json.format[AuthData]

  implicit val plaintextAnswerW : Writes[PlaintextAnswer] =
    (JsPath \ "options").write[Array[Long]] contramap { (t: PlaintextAnswer) => t.options }

  implicit val plaintextAnswerR : Reads[PlaintextAnswer] =
    (JsPath \ "options").read[Array[Long]] map (PlaintextAnswer.apply )

  implicit val PlaintextBallotF = Json.format[PlaintextBallot]
}
