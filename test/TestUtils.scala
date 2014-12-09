package test

import models._
import controllers.routes
import utils.Crypto

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import play.api.db.slick.DB
import org.specs2.specification.AroundOutside
import play.api.Play.current
import com.typesafe.config.ConfigFactory
import play.Configuration
import play.api._
import play.api.libs.json.Json

object TestSettings {
  def getTestApp = FakeApplication(additionalConfiguration = testSettings)

  def testSettings = {
  	import collection.JavaConversions._

    val conf = ConfigFactory.parseFile(new java.io.File("conf/test.local.conf"))
    val map = conf.root().unwrapped()

    map.toMap
  }
}

trait TestContexts {

  object TestData {

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
  }

  def getAuth(permission: String) = {
    val authSecret = Play.current.configuration.getString("booth.auth.secret").get
    val head = permission + ":" + (new java.util.Date().getTime)
    head + ":" + Crypto.hmac(authSecret, head)
  }

  abstract class AppWithDbData(app: FakeApplication = TestSettings.getTestApp) extends WithApplication(app) {
    override def around[T: org.specs2.execute.AsResult](t: => T) = super.around {
      prepareDbWithData()
      org.specs2.execute.AsResult(t)
    }

    def prepareDbWithData() = {
      import scala.slick.jdbc.{GetResult, StaticQuery => Q}

      DB.withSession { implicit session =>
        Q.updateNA("TRUNCATE ELECTION").list
        Q.updateNA("TRUNCATE VOTE").list
      }
    }
  }
}