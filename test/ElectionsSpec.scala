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
package test

import controllers.routes
import utils.Response
import utils.JsonFormatters._

import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.test._
import play.api.test.Helpers._
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.JsSuccess

import scala.util.Try
import scala.util.Failure
import scala.util.Success

import scala.concurrent._

import models._
import play.api.db.slick.DB


@RunWith(classOf[JUnitRunner])
class ElectionsSpec extends Specification with TestContexts with Response {

  "ElectionsApi" should {

    "reject bad auth" in new AppWithDbData() {

      val response = route(FakeRequest(POST, routes.ElectionsApi.register(1).url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", "bogus"))
      ).get

      status(response) must equalTo(FORBIDDEN)
    }

    "allow registering an retrieving an election" in new AppWithDbData() {

      // for this to work we need to set the pk for the election manually (for election 1020)
      var response = route(FakeRequest(POST, routes.ElectionsApi.register(1).url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", getAuth("", "AuthEvent", 1, "edit")))
      ).get

      responseCheck(response, (r:Response[Int]) => r.payload > 0)

      response = route(FakeRequest(GET, routes.ElectionsApi.get(1).url)
        .withJsonBody(TestData.config)
        // .withHeaders(("Authorization", getAuth("", "election", 0, "admin")))
      ).get

      responseCheck(response, (r:Response[ElectionDTO]) => r.payload.configuration.title == "Votación de candidatos")
    }

    "allow updating an election" in new AppWithDbData() {

      DB.withSession { implicit session =>
        val cfg = TestData.config.validate[ElectionConfig].get
        Elections.insert(
          Election(
            /* id = */                        cfg.id,
            /* configuration = */             TestData.config.toString,
            /* state = */                     Elections.REGISTERED,
            /* tally_state = */                     Elections.NO_TALLY,
            /* startDate = */                 cfg.start_date,
            /* endDate = */                   cfg.end_date,
            /* pks = */                       None,
            /* tallyPipesConfig = */          None,
            /* ballotBoxesResultsConfig = */  None,
            /* results = */                   None,
            /* resultsUpdated = */            None,
            /* publishedResults = */          None,
            /* virtual = */                   false,
            /* tallyAllowed = */              false,
            /* publicCandidates = */          true,
            /* logo_url = */                  None,
            /* trusteeKeysState = */          None,
            ///* segmentedMixing = */           None
          )
        )
      }

      val response = route(FakeRequest(POST, routes.ElectionsApi.update(1).url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", getAuth("", "AuthEvent", 1, "edit")))
      ).get

      responseCheck(response, (r:Response[Int]) => r.payload > 0)
    }

    "allow retrieving authority data" in new AppWithDbData() {

      val response = route(
        FakeRequest(GET, routes.ElectionsApi.getAuthorities.url)
          .withHeaders(("Authorization", "bogus"))
      ).get

      responseCheck(
        response,
        (r:Response[Map[String, AuthData]]) => r.payload.size == 2
      )
    }
  }

  def responseCheck[T: Reads](
    result: Future[play.api.mvc.Result],
    f: T => Boolean,
    code:Int = OK
  ) = {

    status(result) must equalTo(code)
    contentType(result) must beSome.which(_ == "application/json")

    val parsed = Try(Json.parse(contentAsString(result))).map(_.validate[T])
    // force it to crash if parse errors
    parsed.get

    parsed must beLike {
      case Success(JsSuccess(response, _)) if f(response) => ok
      case _ => ko
    }
  }
}
