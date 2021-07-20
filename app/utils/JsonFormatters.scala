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

import org.cvogt.play.json.Jsonx
import org.cvogt.play.json.implicits.optionNoError

import play.api.libs._
import json._

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
  implicit val dateDtoF = Json.format[DateDTO]
  implicit val voteF = Json.format[Vote]
  implicit val electionExtraF = Json.format[ElectionExtra]
  implicit val questionConditionF = Json.format[QuestionCondition]
  implicit val conditionalQuestionF = Json.format[ConditionalQuestion]
  implicit val electionF = Json.format[Election]

  implicit val urlF = Json.format[Url]
  implicit val answerF = Json.format[Answer]

  implicit val qExtraF = Jsonx.formatCaseClass[QuestionExtra]

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

  implicit val callbackF = Json.format[Callback]

  // implicit val stringWrites = Json.writes[Option[String]]
  implicit val writeOptionString = new Writes[Option[String]] {
    def writes(ts: Option[String]): JsValue = {
      JsString(ts.getOrElse(""))
    }
  }

  implicit val readOptionShares: Reads[Option[Array[ShareTextItem]]] = optionNoError[Array[ShareTextItem]]
}
