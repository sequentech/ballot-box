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

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ElectionsSpec extends Specification with TestContexts {

  val test = Json.obj(
    "name" -> "frank"
  )

  val config = Json.parse("""
  {
  "election_id": "0000000012",
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
  "voting_start_date": "2013-12-06 18:17:14",
  "voting_end_date": "2013-12-09 18:17:14",
  "is_recurring": false,
  "extra": []
}
""")

  "ElectionsApi" should {

    "allow creating an election" in new AppWithDbData() {
      val response = route(FakeRequest(POST, routes.ElectionsApi.create.url)
        .withJsonBody(config)
        .withHeaders(("Authorization", "hoho"))
      ).get

      responseCheck(response, (r:Int) => r > 0)
    }

    "allow updating an election" in new AppWithDbData() {
      val response = route(FakeRequest(POST, routes.ElectionsApi.update(1).url)
        .withJsonBody(config)
        .withHeaders(("Authorization", "hoho"))
      ).get

      responseCheck(response, (r:Int) => r > 0)
    }
  }

  private def responseCheck[T: Reads](result: Future[play.api.mvc.Result], f: T => Boolean, code:Int = OK) = {
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