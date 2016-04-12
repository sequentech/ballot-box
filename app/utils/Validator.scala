package utils

import org.owasp.html.HtmlPolicyBuilder
import org.owasp.html.Sanitizers

import org.apache.commons.validator.routines._
import scala.util.matching._

import play.api.Play

/**
  * Utility methods for input validation and sanitation
  *
  * depends on
  *
  * http://owasp-java-html-sanitizer.googlecode.com/svn/trunk/distrib/javadoc/org/owasp/html/Sanitizers.html
  * http://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/routines/package-summary.html
  *
  */
object Validator {

  val SHORT_STRING = Play.current.configuration.getInt("election.limits.maxShortStringLength").getOrElse(300)
  val IDENTIFIER_STRING = Play.current.configuration.getInt("election.limits.maxShortStringLength").getOrElse(SHORT_STRING)
  val LONG_STRING = Play.current.configuration.getInt("election.limits.maxLongStringLength").getOrElse(SHORT_STRING*10)

  /*-------------------------------- querying methods  --------------------------------*/

  /** generic regex matcher */
  def isRegex(value: String, regex: Regex) = value match {
    case regex(_*) => true
    case _ => false
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def isSimpleString(value: String) = {
    val simpleRegex = """[\p{L}0-9 _\-]*""".r

    isRegex(value, simpleRegex)
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def isIdentifierString(value: String) = {
    val identifierRegex = """[a-zA-Z0-9_\-]*""".r

    isRegex(value, identifierRegex) && value.length < IDENTIFIER_STRING
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def isString(value: String, limit: Int) = {
    isSimpleString(value) && value.length < limit
  }

  /** allows urls with http/https */
  def isUrl(url: String) = {
    // Get an UrlValidator with custom schemes
    val customSchemes = Array("https","http")
    val customValidator = new UrlValidator(customSchemes)
    customValidator.isValid(url)
  }

  /*-------------------------------- exception throwing methods  --------------------------------*/

  /** if predicate not satisfied throw ValidationException */
  def assert(f: => Boolean, message: String) = {
    if(!f) throw new ValidationException(message)
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def validateString(value: String, limit: Int, message: String) = {
    if(!isString(value, limit)) throw new ValidationException(message)
  }

  /** allows any char */
  def validateStringLength(value: String, limit: Int, message: String) = {
    if(value.length > limit) throw new ValidationException(message)
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def validateIdentifier(value: String, message: String) = {
    if(!isIdentifierString(value)) throw new ValidationException(message)
  }

  /** allows urls with http/https */
  def validateUrl(url: String, message: String) = {
    if(!isUrl(url)) throw new ValidationException(message)
  }

  /*-------------------------------- sanitizing methods  --------------------------------*/

  /**
    * validates html using https://code.google.com/p/owasp-java-html-sanitizer/
    *
    * allows basic formatting elements (b, i) and links
    *
    * see http://owasp-java-html-sanitizer.googlecode.com/svn/trunk/distrib/javadoc/org/owasp/html/Sanitizers.html
    *
    */
  def sanitizeHtml(html: String) = {
    val policy = Sanitizers.FORMATTING.and(Sanitizers.LINKS)
    val safeHTML = policy.sanitize(html)

    safeHTML
  }
}

/** used to signal a validation error when validating votes */
class ValidationException(message: String) extends Exception(message)