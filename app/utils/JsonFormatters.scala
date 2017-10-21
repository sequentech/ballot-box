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


import play.api.libs._
import json._

import shapeless.{ `::` => :#:, _ }
import poly._

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

  /*
  implicit val formatOptTimestamp = new Format[Option[Timestamp]] {
    def writes(ts: Option[Timestamp]): JsValue = {
      JsString(dateFormat.format(ts))
    }
    def reads(ts: JsValue): JsResult[Option[Timestamp]] = {
      try {
        val date = dateFormat.parse(ts.as[String])
        val dateStr = ts.as[String]
        if (dateStr.length > 0) {
          JsSuccess(Some(new Timestamp(date.getTime)))
        } else {
          JsSuccess(None)
        }
      } catch {
        case e: IllegalArgumentException => JsError("Unable to parse timestamp")
      }
    }
  }*/

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
  // it requires shapeless 2.0.0 (newer shapelessversions may not be compatible 
  // with this code)
  // from https://gist.github.com/negator/5139ddb5f6d91cbe7b0c
  // http://stackoverflow.com/questions/23571677/22-fields-limit-in-scala-2-11-play-framework-2-3-case-classes-and-functions


  implicit val writesInstance: LabelledProductTypeClass[Writes] = new LabelledProductTypeClass[Writes] {

      def emptyProduct: Writes[HNil] = Writes(_ => Json.obj())

      def product[F, T <: HList](name: String, FHead: Writes[F], FTail: Writes[T]) = Writes[F :#: T] {
          case head :#: tail =>
              val h = FHead.writes(head)
              val t = FTail.writes(tail)

              (h, t) match {
                  case (JsNull, t: JsObject)     => t
                  case (h: JsValue, t: JsObject) => Json.obj(name -> h) ++ t
                  case _                         => Json.obj()
              }
      }
      def project[F, G](instance: => Writes[G], to: F => G, from: G => F) = Writes[F](f => instance.writes(to(f)))
  }
  object SWrites extends LabelledProductTypeClassCompanion[Writes]

  implicit val readsInstance: LabelledProductTypeClass[Reads] = new LabelledProductTypeClass[Reads] {

      def emptyProduct: Reads[HNil] = Reads(_ => JsSuccess(HNil))

      def product[F, T <: HList](name: String, FHead: Reads[F], FTail: Reads[T]) = Reads[F :#: T] {
          case obj @ JsObject(fields) =>
              for {
                  head <- FHead.reads(obj \ name)
                  tail <- FTail.reads(obj - name)
              } yield head :: tail

          case _ => JsError("Json object required")
      }

      def project[F, G](instance: => Reads[G], to: F => G, from: G => F) = Reads[F](instance.map(from).reads)
  }
  object SReads extends LabelledProductTypeClassCompanion[Reads]

  implicit val formatInstance: LabelledProductTypeClass[Format] = new LabelledProductTypeClass[Format] {
      def emptyProduct: Format[HNil] = Format(
          readsInstance.emptyProduct,
          writesInstance.emptyProduct
      )

      def product[F, T <: HList](name: String, FHead: Format[F], FTail: Format[T]) = Format[F :#: T] (
          readsInstance.product[F, T](name, FHead, FTail),
          writesInstance.product[F, T](name, FHead, FTail)
      )

      def project[F, G](instance: => Format[G], to: F => G, from: G => F) = Format[F](
          readsInstance.project(instance, to, from),
          writesInstance.project(instance, to, from)
      )
  }
  object SFormats extends LabelledProductTypeClassCompanion[Format]
  
  implicit val questionExtraWrites : Writes[QuestionExtra] = SWrites.auto.derive[QuestionExtra]
  implicit val questionExtraReads : Reads[QuestionExtra] = SReads.auto.derive[QuestionExtra]
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
  
  implicit val callbackF = Json.format[Callback]
}
