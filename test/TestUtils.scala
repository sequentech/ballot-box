package test

import models._
import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import play.api.db.slick.DB
import org.specs2.specification.AroundOutside
import controllers.routes
import play.api.Play.current
import com.typesafe.config.ConfigFactory
import play.Configuration
import play.api._

import utils.Crypto

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

        /* Q.updateNA("TRUNCATE ELECTION").list
        Q.updateNA("TRUNCATE VOTE").list */
      }
    }
  }
}