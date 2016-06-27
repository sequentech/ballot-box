/**
 * This file is part of agora_elections.
 * Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

 * agora_elections is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * agora_elections  is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with agora_elections.  If not, see <http://www.gnu.org/licenses/>.
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
        Elections.insert(Election(cfg.id, TestData.config.toString, Elections.REGISTERED, cfg.start_date, cfg.end_date, None, None, None))
      }

      val response = route(FakeRequest(POST, routes.ElectionsApi.update(1).url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", getAuth("", "AuthEvent", 1, "edit")))
      ).get

      responseCheck(response, (r:Response[Int]) => r.payload > 0)
    }

    "allow retrieving authority data" in new AppWithDbData() {

      val response = route(FakeRequest(GET, routes.ElectionsApi.getAuthorities.url)
        .withHeaders(("Authorization", "bogus"))
      ).get

      responseCheck(response, (r:Response[Map[String, AuthData]]) => r.payload.size == 2)
    }
  }

  def responseCheck[T: Reads](result: Future[play.api.mvc.Result], f: T => Boolean, code:Int = OK) = {
    println(s">>> received '${contentAsString(result)}'")

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