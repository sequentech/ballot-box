package test

import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.test._
import play.api.test.Helpers._
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.JsSuccess

import scala.concurrent._
import scala.util.Try
import scala.util.Failure
import scala.util.Success

import controllers.routes
import utils._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class MiscSpec extends Specification {

  "Misc utils" should {

    "allow correct hmac calculation" in {
      HMAC.hmac("pajarraco", "forrarme:1417267291") must beEqualTo("d5c4e961a496559b0a19039bc9cb62a3d9b63b38c54eb14286ca54364d21841e")
    }
  }
}