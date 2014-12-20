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
  "id": 1,
  "title": "Votacion de candidatos",
  "description": "Selecciona los documentos politico, etico y organizativo con los que Podemos",
  "director": "wadobo-auth1",
  "authorities": ["openkratio-authority"],
  "layout": "pcandidates-election",
  "presentation": {
    "share_text": "lo que sea",
    "theme": "foo",
    "urls": [
      {
        "title": "",
        "url": ""
      }
    ],
    "theme_css": "whatever"
  },
  "end_date": "2013-12-09T18:17:14.457000",
  "start_date": "2013-12-06T18:17:14.457000",
  "questions": [
      {
          "description": "",
          "layout": "pcandidates-election",
          "max": 1,
          "min": 0,
          "num_winners": 1,
          "title": "Secretaria General",
          "randomize_answer_order": true,
          "tally_type": "plurality-at-large",
          "answer_total_votes_percentage": "over-total-valid-votes",
          "answers": [
              {
                  "id": 0,
                  "category": "Equipo de Enfermeras",
                  "details": "",
                  "sort_order": 1,
                  "urls": [
                    {
                      "title": "",
                      "url": ""
                    }
                  ],
                  "text": "Fulanita de tal"
              }
          ]
      }
  ]
}
""")
  }

  def getAuth(userId: String, objType: String, objId: Long, perm: String) = {
    val authSecret = Play.current.configuration.getString("booth.auth.secret").get
    val time = (new java.util.Date().getTime / 1000)
    val head = s"$userId:$objType:$objId:$perm:$time"

    "khmac:///sha-256;" + Crypto.hmac(authSecret, head) + "/" + head
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