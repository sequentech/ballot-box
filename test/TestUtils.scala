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

  abstract class AppWithDbData(app: FakeApplication = TestSettings.getTestApp) extends WithApplication(app) {
    override def around[T: org.specs2.execute.AsResult](t: => T) = super.around {
      prepareDbWithData()
      org.specs2.execute.AsResult(t)
    }

    def prepareDbWithData() = {
    }
  }
}