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

      val response = route(FakeRequest(POST, routes.ElectionsApi.register.url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", "bogus"))
      ).get

      status(response) must equalTo(FORBIDDEN)
    }

    "allow registering an retrieving an election" in new AppWithDbData() {

      // for this to work we need to set the pk for the election manually (for election 1020)
      var response = route(FakeRequest(POST, routes.ElectionsApi.register.url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", getAuth("", "AuthEvent", 0, "admin")))
      ).get

      responseCheck(response, (r:Response[Int]) => r.payload > 0)

      response = route(FakeRequest(GET, routes.ElectionsApi.get(1).url)
        .withJsonBody(TestData.config)
        // .withHeaders(("Authorization", getAuth("", "election", 0, "admin")))
      ).get

      responseCheck(response, (r:Response[ElectionDTO]) => r.payload.configuration.title == "Votacion de candidatos")
    }

    "allow updating an election" in new AppWithDbData() {

      DB.withSession { implicit session =>
        val cfg = TestData.config.validate[ElectionConfig].get
        Elections.insert(Election(cfg.id, TestData.config.toString, Elections.REGISTERED, cfg.start_date, cfg.end_date, None, None, None))
      }

      val response = route(FakeRequest(POST, routes.ElectionsApi.update(1).url)
        .withJsonBody(TestData.config)
        .withHeaders(("Authorization", getAuth("", "AuthEvent", 1, "admin")))
      ).get

      responseCheck(response, (r:Response[Int]) => r.payload > 0)
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