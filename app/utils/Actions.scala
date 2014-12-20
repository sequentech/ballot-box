package utils

import play.api.mvc.Results._
import play.api.mvc._
import play.api.Logger
import scala.concurrent._
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.{Crypto => PlayCrypto}


/** Authorizes requests using hmac in Authorization header */
case class HMACAuthAction(allowed: String, data: List[Any] = List()) extends ActionFilter[Request] {

  val boothSecret = Play.current.configuration.getString("booth.auth.secret").get
  val expiry = Play.current.configuration.getString("booth.auth.expiry").get.toInt
  val regexp = """\$\{([a-zA-Z0-9]+)\}""".r

  /** deny requests that dont pass hmac validations */
  def filter[A](input: Request[A]) = Future.successful {

    input.headers.get("Authorization").map(validate(input)) match {
      case Some(true) => None
      case _ => Some(Forbidden)
    }
  }

  /** validate an hmac authorization code */
  def validate[A](request: Request[A])(value: String): Boolean = {

    try {
      // expand index variables ($0 $1 etc)
      var expanded = allowed
      data.zipWithIndex.foreach { case(d, index) =>
        expanded = expanded.replace("$" + index, d.toString)
      }

      val split = value.split(':')
      if(split.length != 3) {
        Logger.warn(s"Malformed authorization header")
        return false
      }

      val permission = split(0)
      val time = split(1).toLong
      val hash = split(2)
      val now = new java.util.Date().getTime
      val diff = now - time

      if( (diff < expiry) && PlayCrypto.constantTimeEquals(Crypto.hmac(boothSecret, s"$permission:$time"), hash) && (expanded == permission) ) {

        return true
      }

      Logger.warn(s"Failed to authorize request with perm $value and allowed $allowed")
      return false
    }
    catch {
      case e:Exception => Logger.warn(s"Exception verifying hmac ($value)", e); false
    }
  }
}

/** an action that passes through the hmac filter */
case class HAction(allowed: String, data: List[Any] = List()) extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    HMACAuthAction(allowed, data).invokeBlock(request, block)
  }
}
/** Authorizes requests using hmac in Authorization header */
case class HMACAuthAction2(userId: String, objType: String, objId: Long, perm: String) extends ActionFilter[Request] {

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
      val slashPos = value.length + start.length;

      if(!value.startsWith(start) || value.length < slashPos ||
        value.charAt(slashPos) != '/') {
        Logger.warn(s"Malformed authorization header")
        return false
      }
      val hash = value.substring(start.length, slashPos)
      val message = value.substring(slashPos + 1)

      val split = value.split(':')
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

      // note that we can compare without doing contant time comparison received
      // strings because that's not critical for security, only hmac is
      if(PlayCrypto.constantTimeEquals(Crypto.hmac(boothSecret, message), hash) &&
        (diff < expiry) && (rcvUserId == userId) && (rcvObjType == objType) &&
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
case class HAction2(userId: String, objType: String, objId: Long, perm: String) extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    HMACAuthAction2(userId, objType, objId, perm).invokeBlock(request, block)
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

// UNUSED

/** Logs before each request is processed */
object LoggingAction extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    Logger.info(s"processing ${request.path}")
    block(request)
  }
}

/** pipeline: LoggingAction and then HMACAction */
case class LHAction(allowed: String, data: List[Any] = List()) extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    (LoggingAction andThen HMACAuthAction(allowed, data)).invokeBlock(request, block)
  }
}