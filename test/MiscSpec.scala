/**
 * This file is part of ballot_box.
 * Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

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

    /** this does not work as the dumped votes vary each test, you can test sha256 using echo "blabla" | sha512sum to verify */
    /* "allow correct hashing of file" in new WithApplication() {
      Datastore.hashVotes(1) must beEqualTo("cf53a19dc191e3af395e463b8664578d4daa8770c4e98d0ab863ffdd47784f18")
    } */
  }
}