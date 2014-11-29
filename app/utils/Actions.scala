package utils

import play.api.mvc.Results._
import play.api.mvc._
import play.api.Logger
import scala.concurrent._


object LoggingAction extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    Logger.info("Calling action")
    block(request)
  }
}

case class HmacAuthAction(required: String) extends ActionFilter[Request] {
  def filter[A](input: Request[A]) = Future.successful {

    input.headers.get("Authorization").map(validate(input)) match {
      case Some(true) => None
      case _ => Some(Forbidden)
    }
  }

  def validate[A](request: Request[A])(value: String): Boolean = {
    return value == required
  }
}

case class LHAction(required: String) extends ActionBuilder[Request] {
  def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
    (LoggingAction andThen HmacAuthAction(required)).invokeBlock(request, block)
  }
}