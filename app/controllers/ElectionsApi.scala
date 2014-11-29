package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json.Json
import play.api.libs.json.JsError
import play.api.Play.current
import play.api.db.slick.DB
import play.libs.Akka

import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import models._
import utils.JsonFormatters._
import utils._

object ElectionsApi extends Controller {

  // http://stackoverflow.com/questions/19780545/play-slick-with-securesocial-running-db-io-in-a-separate-thread-pool
  // https://github.com/playframework/play-slick/issues/105
  // implicit val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")

  def create = LHAction("hoho").async(BodyParsers.parse.json) { request =>
    Future {
      val electionConfig = request.body.validate[ElectionConfig]

      electionConfig.fold(
        errors => {
          BadRequest(Json.obj("status" ->"KO", "message" -> JsError.toFlatJson(errors)))
        },
        election => {
          DB.withSession { implicit session =>
            val result = Elections.insert(Election(None, election.election_id, request.body.toString, election.voting_start_date, election.voting_end_date))
            Ok(Json.toJson(result))
          }
        }
      )
    }
  }

  def update(id: Long) = LHAction("hoho").async(BodyParsers.parse.json) { request =>
    Future {
      val electionConfig = request.body.validate[ElectionConfig]

      electionConfig.fold(
        errors => {
          BadRequest(Json.obj("status" ->"KO", "message" -> JsError.toFlatJson(errors)))
        },
        election => {
          DB.withSession { implicit session =>
            val result = Elections.update(id, Election(None, election.election_id, request.body.toString, election.voting_start_date, election.voting_end_date))
            Ok(Json.toJson(result))
          }
        }
      )
    }
  }

  def start(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }

  def stop(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }

  def tally(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }

  def calculateResults(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }

  def publishResults(id: Long) = LHAction("hoho") { request =>
    Ok(Json.toJson(0))
  }
}