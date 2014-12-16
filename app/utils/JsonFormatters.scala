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
    def writes(ts: Timestamp): JsValue = JsString(ts.toString())
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
  implicit val electionF = Json.format[Election]
  implicit val urlF = Json.format[Url]
  implicit val answerF = Json.format[Answer]
  implicit val questionF = Json.format[Question]

  implicit val presentationF = Json.format[ElectionPresentation]
  implicit val configF = Json.format[ElectionConfig]

  implicit val publicKeyF = Json.format[PublicKey]
  implicit val publicKeySessionF = Json.format[PublicKeySession]
  implicit val createResponseF = Json.format[CreateResponse]

  implicit val electionHashF = Json.format[ElectionHash]
  implicit val popkF = Json.format[Popk]
  implicit val choiceF = Json.format[Choice]
  implicit val encryptedVoteF = Json.format[EncryptedVote]
  implicit val rawVoteF = Json.format[RawVote]

  implicit val tallyDataF = Json.format[TallyData]
  implicit val tallyResponseF = Json.format[TallyResponse]
}