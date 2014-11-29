package utils

import play.api.libs.json._
import play.api.libs.functional.syntax._
import models._
import java.sql.Timestamp

object JsonFormatters {
  implicit val formatTimestamp = new Format[Timestamp] {
    def writes(ts: Timestamp): JsValue = JsString(ts.toString())
    def reads(ts: JsValue): JsResult[Timestamp] = {
      try {
        JsSuccess(Timestamp.valueOf(ts.as[String]))
      } catch {
        case e: IllegalArgumentException => JsError("Unable to parse timestamp")
      }
    }
  }

  implicit val voteF = Json.format[Vote]
  implicit val electionF = Json.format[Election]
  implicit val answerF = Json.format[Answer]
  implicit val questionF = Json.format[Question]

  implicit val configF = Json.format[ElectionConfig]

}