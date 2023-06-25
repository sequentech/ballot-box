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
package utils

import play.api.libs.json._
import java.util.Date

/**
  * Utilities for json messaging
  *
  */
trait Response {
  case class Response[T: Writes](payload: T)
  case class Error(error: String, code: Int)

  object ErrorCodes {
    val MISSING_AUTH = 1
    val EO_ERROR = 2
    val PK_ERROR = 3
    val TALLY_ERROR = 4
    val GENERAL_ERROR = 5
    val NO_ELECTION = 6
    val NO_PKS = 7
  }

  implicit val errorFormatter = Json.format[Error]

  object AuthErrorCodes {
    val MISSING_USER_CREDENTIALS = "MISSING_USER_CREDENTIALS"
    val MALFORMED_USER_CREDENTIALS = "MALFORMED_USER_CREDENTIALS"
    val INVALID_USER_CREDENTIALS = "INVALID_USER_CREDENTIALS"

    val BAD_REQUEST = "BAD_REQUEST"
    val INVALID_REQUEST = "INVALID_REQUEST"
    val INVALID_PERMS = "INVALID_PERMS"
    val GENERAL_ERROR = "GENERAL_ERROR"
    val MAX_CONNECTION = "MAX_CONNECTION"
    val BLACKLIST = "BLACKLIST"
    val AUTH_EVENT_NOT_FOUND = "AUTH_EVENT_NOT_FOUND"
    val AUTH_EVENT_NOT_STARTED = "AUTH_EVENT_NOT_STARTED"
    val CANT_VOTE_MORE_TIMES = "CANT_VOTE_MORE_TIMES"
    val CANT_AUTHENTICATE_TO_PARENT = "CANT_AUTHENTICATE_TO_PARENT"
    val INVALID_FIELD_VALIDATION = "INVALID_FIELD_VALIDATION"
    val PIPELINE_INVALID_CREDENTIALS = "PIPELINE_INVALID_CREDENTIALS"

    /** Note that for security reasons the following three error codes are the
      * same. This is to prevent enumeration attacks. More information:
      * https://web.archive.org/web/20230203194955/https://www.techtarget.com/searchsecurity/tip/What-enumeration-attacks-are-and-how-to-prevent-them */
    val INVALID_CODE = "INVALID_USER_CREDENTIALS"
    val USER_NOT_FOUND = "INVALID_USER_CREDENTIALS"
    val INVALID_PASSWORD_OR_CODE = "INVALID_USER_CREDENTIALS"
  }

    /** need to manually write reads/writes for generic types */
  implicit def responseReads[T: Format]: Reads[Response[T]] = new Reads[Response[T]] {
    def reads(json: JsValue): JsResult[Response[T]] = JsSuccess(new Response[T] (
       (json \ "payload").as[T]
    ))
  }

  /** need to manually write reads/writes for generic types */
  implicit def responseWrites[T: Writes]: Writes[Response[T]] = new Writes[Response[T]] {
    def writes(response: Response[T]) = JsObject(Seq(
        "date" -> JsString(new java.sql.Timestamp(new Date().getTime).toString),
        "payload" -> Json.toJson(response.payload)
    ))
  }

  def error(error: String, code: Int = ErrorCodes.GENERAL_ERROR) = {
    Json.toJson(Response(Error(error, code)))
  }

  def response[T: Writes](payload: T) = {
    Json.toJson(Response(payload))
  }
}