package utils

import play.api.mvc.Results._
import play.api.mvc._
import play.api.Logger
import scala.concurrent._
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.{Crypto => PlayCrypto}

/** Authorizes requests using hmac in Authorization header */
case class HMACAuthAction(userId: String, objType: String, objId: Long, perm: String) extends ActionFilter[Request] {

  val boothSecret = Play.current.configuration.getString("booth.auth.secret").get
  val expiry = Play.current.configuration.getString("booth.auth.expiry").get.toInt

  /** deny requests that dont pass hmac validations */
  def filter[A](input: Request[A]) = Future.successful {

    input.headers.get("Authorization").map(validate(input)) match {
      case Some(true) => None
      case _ => Some(Forbidden)
    }
  }

  /** validate an hmac authorization code

   Format is: "khmac://sha-256;<hash>/<message>"

   Format of the message is:
   "<userid:String>:<obj_type:String>:<obj_id:Long>:<perm:String>:<time:Long>"
   */
  def validate[A](request: Request[A])(value: String): Boolean = {

    try {
      val start = "khmac:///sha-256;";
      val slashPos = start.length + 64;

      if(!value.startsWith(start) || value.length < slashPos ||
        value.charAt(slashPos) != '/') {
        Logger.warn(s"Malformed authorization header")
        return false
      }
      val hash = value.substring(start.length, slashPos)
      val message = value.substring(slashPos + 1)

      val split = message.split(':')
      if(split.length != 5) {
        Logger.warn(s"Malformed authorization header")
        return false
      }

      val rcvUserId = split(0)
      val rcvObjType = split(1)
      val rcvObjId = split(2).toLong
      val rcvPerm = split(3)
      val rcvTime = split(4).toLong
      val now = new java.util.Date().getTime / 1000
      val diff = now - rcvTime

      val compareOk = PlayCrypto.constantTimeEquals(Crypto.hmac(boothSecret, message), hash)

      // Logger.info(Crypto.hmac(boothSecret, message))
      // Logger.info(hash)
      // Logger.info(compareOk + " " + (diff < expiry) + " " + (rcvUserId == userId) + " " + (rcvObjType == objType) + " " + (rcvObjId == objId) + " " + (rcvPerm == perm))

      // note that we can compare without doing contant time comparison received
      // strings because that's not critical for security, only hmac is
      if(compareOk && (diff < expiry) && (rcvUserId == userId) && (rcvObjType == objType) &&
        (rcvObjId == objId) && (rcvPerm == perm)) {

        return true
      }

      Logger.warn(s"Failed to authorize hmac ($value)")
      return false
    }
    catch {
      case e:Exception => Logger.warn(s"Exception verifying hmac ($value)", e); false
    }
  }
}

/** an action that passes through the hmac filter */
case class HAction(userId: String, objType: String, objId: Long, perm: String) extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    HMACAuthAction(userId, objType, objId, perm).invokeBlock(request, block)
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