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

// FIXME add legendre tests
/** */
@RunWith(classOf[JUnitRunner])
class MiscSpec extends Specification {

  "Misc utils" should {

    "allow correct hmac calculation" in {
      Crypto.hmac("pajarraco", "forrarme:1417267291") must beEqualTo("d5c4e961a496559b0a19039bc9cb62a3d9b63b38c54eb14286ca54364d21841e")
    }

    /** this does not work as the dumped votes vary each test, you can test sha256 using http://www.xorbin.com/tools/sha256-hash-calculator */
    /* "allow correct hashing of file" in new WithApplication() {
      Datastore.hashVotes(1) must beEqualTo("cf53a19dc191e3af395e463b8664578d4daa8770c4e98d0ab863ffdd47784f18")
    } */
  }
}