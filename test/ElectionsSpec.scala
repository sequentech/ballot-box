package test

import controllers.routes
import utils.Response

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

  val test = Json.obj(
    "name" -> "frank"
  )

  val config = Json.parse("""
  {
  "election_id": 1,
  "director": "wadobo-auth1",
  "authorities": ["wadobo-auth3"],
  "title": "Test election",
  "url": "https://example.com/election/url",
  "description": "election description",
  "questions_data": [{
      "question": "Who Should be President?",
      "tally_type": "ONE_CHOICE",
      "answers": [
          {"a": "ballot/answer",
          "details": "",
          "value": "Alice"},
          {"a": "ballot/answer",
          "details": "",
          "value": "Bob"}
      ],
      "max": 1, "min": 0
  }],
  "voting_start_date": "2015-12-06T18:17:14.457",
  "voting_end_date": "2015-12-09T18:17:14.457",
  "is_recurring": false,
  "extra": []
}
""")

  "ElectionsApi" should {

    "reject bad auth" in new AppWithDbData() {

      val response = route(FakeRequest(POST, routes.ElectionsApi.register.url)
        .withJsonBody(config)
        .withHeaders(("Authorization", "bogus"))
      ).get

      status(response) must equalTo(FORBIDDEN)
    }

    "allow registering an election" in new AppWithDbData() {

      // for this to work we need to set the pk for the election manually (for election 1020)
      DB.withSession { implicit session =>
        Elections.delete(1)
      }

      val response = route(FakeRequest(POST, routes.ElectionsApi.register.url)
        .withJsonBody(config)
        .withHeaders(("Authorization", getAuth("register")))
      ).get

      responseCheck(response, (r:Response[Int]) => r.payload > 0)
    }

    "allow updating an election" in new AppWithDbData() {
      val response = route(FakeRequest(POST, routes.ElectionsApi.update(1).url)
        .withJsonBody(config)
        .withHeaders(("Authorization", getAuth("update-1")))
      ).get

      responseCheck(response, (r:Int) => r > 0)
    }
  }

  def responseCheck[T: Reads](result: Future[play.api.mvc.Result], f: T => Boolean, code:Int = OK) = {
    println(s">>> received '${contentAsString(result)}'")

    status(result) must equalTo(code)
    contentType(result) must beSome.which(_ == "application/json")

    val parsed = Try(Json.parse(contentAsString(result))).map(_.validate[T])

    parsed must beLike {
      case Success(JsSuccess(response, _)) if f(response) => ok
      case _ => ko
    }
  }
}