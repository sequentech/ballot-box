package utils

import org.owasp.html.HtmlPolicyBuilder
import org.owasp.html.Sanitizers

import org.apache.commons.validator.routines._
import scala.util.matching._

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

  /*-------------------------------- querying methods  --------------------------------*/

  /** generic regex matcher */
  def isRegexOk(value: String, regex: Regex) = value match {
    case regex(_*) => true
    case _ => false
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def isSimpleString(value: String) = {
    val basicRegex = """[\p{L}0-9 _\-]*""".r

    isRegexOk(value, basicRegex)
  }

  /** allows characters, space, numbers, underscore and hyphen */
  def isStringOk(value: String, limit: Int) = {
    isSimpleString(value) && value.length < limit
  }

  /** allows urls with http/https */
  def isUrlOk(url: String) = {
    // Get an UrlValidator with custom schemes
    val customSchemes = Array("https","http")
    val customValidator = new UrlValidator(customSchemes)
    customValidator.isValid(url)
  }

  /*-------------------------------- exception throwing methods  --------------------------------*/

  /** allows characters, space, numbers, underscore and hyphen */
  def validateString(value: String, limit: Int, message: String) = {
    if(!isStringOk(value, limit)) throw new ValidationException(message)
  }

  /** allows urls with http/https */
  def validateUrl(url: String, message: String) = {
    if(!isUrlOk(url)) throw new ValidationException(message)
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