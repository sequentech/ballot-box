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

import play.api.mvc.Results._
import play.api.mvc._
import play.api.Logger
import scala.concurrent._
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.{Crypto => PlayCrypto}

case class HMACActionHelper(
  userId: String,
  objType: String,
  objId: Long,
  perm: String,
  expiry: Int,
  boothSecret: String,
  authorizationHeader: String
) extends Response {
  def check(): Either[String, Boolean] =
  {
    try {
      val start = "khmac:///sha-256;";
      val slashPos = start.length + 64;

      if(
        !authorizationHeader.startsWith(start) ||
        authorizationHeader.length < slashPos ||
        authorizationHeader.charAt(slashPos) != '/'
      ) {
        Logger.warn(s"Malformed authorization header")
        return Left(AuthErrorCodes.MALFORMED_USER_CREDENTIALS)
      }
      val hash = authorizationHeader.substring(start.length, slashPos)
      val message = authorizationHeader.substring(slashPos + 1)

      val split = message.split(':')
      if (split.length < 7) {
        Logger.warn(s"Malformed authorization header")
        return Left(AuthErrorCodes.MALFORMED_USER_CREDENTIALS)
      }

      val rcvUserId = split.slice(0, split.length - 6).mkString(":")
      val rcvObjType = split(split.length - 6)
      val rcvObjId = split(split.length - 5).toLong
      val rcvPerm = split(split.length - 4)
      val rcvExpiryTime = split(split.length - 3).toLong
      val rcvToken = split(split.length - 2)
      val rcvTime = split(split.length - 1).toLong
      val now = new java.util.Date().getTime / 1000
      val diff = now - rcvTime

      val compareOk = PlayCrypto.constantTimeEquals(Crypto.hmac(boothSecret, message), hash)
      val permsOk = !perm.split('|').toSet
        .intersect(rcvPerm.split('|').toSet)
        .isEmpty

      // Logger.info(Crypto.hmac(boothSecret, message))
      // Logger.info(hash)
      // Logger.info(compareOk + " " + (diff < expiry) + " " + (rcvUserId == userId) + " " + (rcvObjType == objType) + " " + (rcvObjId == objId) + " " + permsOk)

      // if the userId is the empty string we don't mind the user
      val userOk = (rcvUserId == userId || userId == "")

      // Check if the current time is within the expiry timestamp
      val withinExpiry = now <= rcvExpiryTime

      // note that we can compare without doing contant time comparison received
      // strings because that's not critical for security, only hmac is
      if(compareOk && withinExpiry && userOk && (rcvObjType == objType) &&
        (rcvObjId == objId) && permsOk)
      {
        return Right(true)
      }

      Logger.warn(
        s"Failed to authorize hmac:\n\tauthorizationHeader=$authorizationHeader\tcompareOk=$compareOk\n\tdiff=$diff\n\texpiry=$expiry\n\tuserOk=$userOk\n\trcvObjType=$rcvObjType\n\tobjType=$objType\n\trcvObjId=$rcvObjId\n\tobjId=$objId\n\trcvPerm=$rcvPerm\n\tperm=$perm\n\tnow=$now\n\trcvExpiryTime=$rcvExpiryTime\n\t"
      )
      return Left(AuthErrorCodes.INVALID_USER_CREDENTIALS)
    }
    catch {
      case e:Exception => {
        Logger.warn(s"Exception verifying hmac ($authorizationHeader)", e)
      return Left(AuthErrorCodes.INVALID_USER_CREDENTIALS)
      }
    }
  }

  def flatCheck: Boolean = check() match {
      case Right(true) => true
      case _ => false
    }
}

/** Authorizes requests using hmac in Authorization header */
case class HMACAuthAction(
  userId: String, 
  objType: String, 
  objId: Long, 
  perm: String, 
  expiry: Int
) extends ActionFilter[Request] with Response {

  val boothSecret = Play.current.configuration.getString("elections.auth.secret").get

  /** deny requests that dont pass hmac validations */
  def filter[A](input: Request[A]) = Future.successful {

    input.headers.get("Authorization") match {
      case Some(authValue) => 
        val inputValidated: Either[String, Boolean] = check(input)(authValue)
        inputValidated match {
          case Right(true) => None
          case Left(code) => Some(Forbidden(error(code)))
          case _ => Some(Forbidden(error(AuthErrorCodes.MALFORMED_USER_CREDENTIALS)))
        }
      case None => Some(Forbidden(error(AuthErrorCodes.MISSING_USER_CREDENTIALS)))
    }
  }

  /** validate an hmac authorization code

   Format is: "khmac://sha-256;<hash>/<message>"

   Format of the message is:
   "<userid:String>:<obj_type:String>:<obj_id:Long>:<perm:String>:<time:Long>"
   */
  def validate[A](request: Request[A])(value: String): Boolean = {
    HMACActionHelper(
      userId,
      objType,
      objId,
      perm,
      expiry,
      boothSecret,
      value
    ).flatCheck
  }

  def check[A](request: Request[A])(value: String): Either[String, Boolean] = {
    HMACActionHelper(
      userId,
      objType,
      objId,
      perm,
      expiry,
      boothSecret,
      value
    ).check
  }
}

/** an action that passes through the hmac filter */
case class HAction(
  userId: String,
  objType: String, 
  objId: Long,
  perm: String
) extends ActionBuilder[Request] 
{
  def invokeBlock[A](
    request: Request[A],
    block: (Request[A]) => Future[Result]
  ) = {
    HMACAuthAction(
      userId, 
      objType, 
      objId, 
      perm,
      Play.current.configuration.getString("elections.auth.expiry").get.toInt
    ).invokeBlock(request, block)
  }
}

/** an action that passes through the hmac filter */
case class HActionAdmin(
  userId: String,
  objType: String,
  objId: Long,
  perm: String
) extends ActionBuilder[Request]
{
  def invokeBlock[A](
    request: Request[A],
    block: (Request[A]) => Future[Result]
  ) = {
    HMACAuthAction(
      userId, 
      objType, 
      objId, 
      perm,
      Play.current.configuration.getString("elections.auth.admin_expiry").get.toInt
    ).invokeBlock(request, block)
  }
}

// https://www.playframework.com/documentation/2.3.x/ScalaHttpFilters
/** logs and times request processing */
object LoggingFilter extends Filter {
  def apply(nextFilter: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val startTime = System.currentTimeMillis
    nextFilter(requestHeader).map { result =>
      val endTime = System.currentTimeMillis
      val requestTime = endTime - startTime
      Logger.info(s"${requestHeader.method} ${requestHeader.uri} " + s"took ${requestTime}ms and returned ${result.header.status}")
      result.withHeaders("Request-Time" -> requestTime.toString)
    }
  }
}
