/**
 * This file is part of agora_elections.
 * Copyright (C) 2017  Agora Voting SL <nvotes@nvotes.com>

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
package commands

import models._
import utils.JsonFormatters._
import utils.Crypto

import java.util.concurrent.Executors
import scala.concurrent._

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import java.math.BigInteger
import scala.concurrent.forkjoin._
import scala.collection.mutable.ArrayBuffer
import scala.util.{Try, Success, Failure}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.libs.Akka

import play.api.libs.json._

import java.nio.file.{Paths, Files, Path}
import java.nio.file.StandardOpenOption._
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.RandomAccessFile
import java.nio._

import play.api.Play.current
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.libs.ws.ning.NingWSClient
import play.api.mvc._
import play.api.http.{Status => HTTP}


case class Answer(options: Array[Long] = Array[Long]())
// id is the election ID
case class PlaintextBallot(id: Long = -1, answers: Array[Answer] = Array[Answer]())
case class PlaintextError(message: String) extends Exception(message)
case class DumpPksError(message: String) extends Exception(message)
case class BallotEncryptionError(message: String) extends Exception(message)
case class GetElectionInfoError(message: String) extends Exception(message)

object PlaintextBallot {
  val ID = 0
  val ANSWER = 1
}


class VotesWriter(filePath: Path) {
  Files.write(filePath, "".getBytes(StandardCharsets.UTF_8), CREATE, TRUNCATE_EXISTING)
  def write(id: Long, ballot: EncryptedVote) = Future {
    val content: String = id.toString + "-" + Json.toJson(ballot).toString + "\n"
    this.synchronized {
      Files.write(filePath, content.getBytes(StandardCharsets.UTF_8), APPEND)
    }
  }
}
/**
  * Command for admin purposes
  *
  * use runMain commands.Command <args> from console
  * or
  * activator "run-main commands.Command <args>"
  * from CLI
  */
object Console {
  //implicit val ec = ExecutionContext.fromExecutor(new ForkJoinPool(100))
  //implicit val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")

  var vote_count : Long = 0
  var host = "localhost"
  var port : Long = 9000
  var plaintexts_path = "plaintexts.txt"
  var ciphertexts_path = "ciphertexts.csv"
  var shared_secret = "<password>"
  var datastore = "/home/agoraelections/datastore"

  // copied from https://www.playframework.com/documentation/2.3.x/ScalaWS
  val clientConfig = new DefaultWSClientConfig()
  val secureDefaults:com.ning.http.client.AsyncHttpClientConfig = new NingAsyncHttpClientConfigBuilder(clientConfig).build()
  val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder(secureDefaults)
  builder.setCompressionEnabled(true)
  val secureDefaultsWithSpecificOptions:com.ning.http.client.AsyncHttpClientConfig = builder.build()
  implicit val wsClient = new play.api.libs.ws.ning.NingWSClient(secureDefaultsWithSpecificOptions)


  private def parse_args(args: Array[String]) = {
    var arg_index = 0
    while (arg_index + 2 < args.length) {
      // number of ballots to create
      if ("--vote-count" == args(arg_index + 1)) {
        vote_count = args(arg_index + 2).toLong
        arg_index += 2
      }
      else if ("--plaintexts" == args(arg_index + 1)) {
        plaintexts_path = args(arg_index + 2)
        arg_index += 2
      } else if ("--ciphertexts" == args(arg_index + 1)) {
        ciphertexts_path = args(arg_index + 2)
        arg_index += 2
      } else if ("--shared-secret" == args(arg_index + 1)) {
        shared_secret = args(arg_index + 2)
        arg_index += 2
      } else if ("--port" == args(arg_index + 1)) {
        port = args(arg_index + 2).toLong
        if (port <= 0 || port > 65535) {
          throw new java.lang.IllegalArgumentException(s"Invalid port $port")
        }
        arg_index += 2
      } else {
        throw new java.lang.IllegalArgumentException("Unrecognized argument: " + args(arg_index + 1))
      }
    }
  } 

  private def showHelp() = {
    System.out.println("showHelp")
  }

  private def processPlaintextLine(line: String, lineNumber: Long) : PlaintextBallot  = {
    var strIndex: Option[String] = None
    var state = PlaintextBallot.ID
    var ballot = PlaintextBallot()
    var optionsBuffer: Option[ArrayBuffer[Long]] = None
    var answersBuffer: ArrayBuffer[Answer] = ArrayBuffer[Answer]()
    for (i <- 0 until line.length) {
      val c = line.charAt(i)
      if(c.isDigit) { // keep reading digits till we get the whole number
        strIndex match {
          // add a character to the string containing a number
          case Some(strIndexValue) =>
            strIndex = Some(strIndexValue + c.toString)
          case None => ()
            // add the first character to the string containing a number
            strIndex = Some(c.toString)
        }
      } else {
        if (PlaintextBallot.ID == state) {
          if ('|' != c) {
              throw PlaintextError(s"Error on line $lineNumber, character $i: character separator '|' not found after election index . Line: $line")
          }
          strIndex match {
            case Some(strIndexValue) =>
              ballot = PlaintextBallot(strIndex.get.toLong, ballot.answers)
              strIndex = None
              optionsBuffer = Some(ArrayBuffer[Long]())
              state = PlaintextBallot.ANSWER
            case None =>
              throw PlaintextError(s"Error on line $lineNumber, character $i: election index not recognized. Line: $line")
          }
        } else if (PlaintextBallot.ANSWER == state) {
          optionsBuffer match {
            case Some(optionsBufferValue) =>
              if ('|' == c) {
                if (strIndex.isDefined) {
                  optionsBufferValue += strIndex.get.toLong
                  strIndex = None
                }
                answersBuffer += Answer(optionsBufferValue.toArray)
                optionsBuffer = Some(ArrayBuffer[Long]())
              } else if(',' == c) {
                strIndex match {
                  case Some(strIndexValue) =>
                    optionsBufferValue += strIndexValue.toLong
                    strIndex = None
                  case None =>
                    throw PlaintextError(s"Error on line $lineNumber, character $i: option number not recognized before comma on question ${ballot.answers.length}. Line: $line")
                }
              } else {
                throw PlaintextError(s"Error on line $lineNumber, character $i: invalid character: $c. Line: $line")
              }
            case None =>
              PlaintextError(s"Error on line $lineNumber, character $i: unknown error, invalid state. Line: $line")
          }
        }
      }
    }
    // add the last Answer
    optionsBuffer match {
      case Some(optionsBufferValue) =>
        answersBuffer += Answer(optionsBufferValue.toArray)
      case None =>
        throw PlaintextError(s"Error on line $lineNumber: unknown error, invalid state. Line: $line")
    }
    ballot = PlaintextBallot(ballot.id, answersBuffer.toArray)
    ballot
  }

  private def parsePlaintexts(): Future[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Long])] = {
    val promise = Promise[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Long])]()
    Future {
      if (Files.exists(Paths.get(plaintexts_path))) {
        val ballotsList = scala.collection.mutable.ListBuffer[PlaintextBallot]()
        val electionsSet = scala.collection.mutable.LinkedHashSet[Long]()
        io.Source.fromFile(plaintexts_path).getLines().zipWithIndex.foreach { 
          case (line, number) =>
              val ballot = processPlaintextLine(line, number)
              ballotsList += ballot
              electionsSet += ballot.id
        }
        if ( electionsSet.isEmpty || ballotsList.isEmpty ) {
          throw PlaintextError("Error: no ballot found")
        } else {
          promise success ( ( ballotsList.sortBy(_.id).toList, electionsSet.toSet ) )
        }
      } else {
        throw new java.io.FileNotFoundException("tally does not exist")
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def get_khmac(userId: String, objType: String, objId: Long, perm: String) : String = {
    val now: Long = System.currentTimeMillis / 1000
    val message = s"$userId:$objType:$objId:$perm:$now"
    val hmac = Crypto.hmac(shared_secret, message)
    val khmac = s"khmac:///sha-256;$hmac/$message"
    khmac
  }

  private def get_election_info(electionId: Long) : Future[ElectionDTO] = {
    val promise = Promise[ElectionDTO]
    Future {
      val url = s"http://$host:$port/api/election/$electionId"
      wsClient.url(url) .get() map { response =>
        if(response.status == HTTP.ACCEPTED) {
          val dto = response.json.validate[ElectionDTO].get
          promise success dto
        } else {
          promise failure GetElectionInfoError(s"HTTP GET request to $url returned status: ${response.status} and body: ${response.body}")
        }
      } recover { case error: Throwable =>
        promise failure error
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def get_election_info_all(electionsSet: scala.collection.immutable.Set[Long]) : Future[scala.collection.mutable.HashMap[Long, ElectionDTO]] = {
    val promise = Promise[scala.collection.mutable.HashMap[Long, ElectionDTO]]()
    Future {
      val map = scala.collection.mutable.HashMap[Long, ElectionDTO]()
      electionsSet.par.foreach { eid =>
        get_election_info(eid) map { dto : ElectionDTO =>
          this.synchronized {
            map += (dto.id -> dto)
          }
        }
      }
      promise success ( map )
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def dump_pks(electionId: Long): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      val auth = get_khmac("", "AuthEvent", electionId, "edit")
      val url = s"http://$host:$port/api/election/$electionId/dump-pks"
      wsClient.url(url)
        .withHeaders("Authorization" -> auth)
        .post(Results.EmptyContent()).map { response =>
          if(response.status == HTTP.ACCEPTED) {
            promise success ( () )
          } else {
            promise failure DumpPksError(s"HTTP POST request to $url returned status: ${response.status} and body: ${response.body}")
          }
      } recover { case error: Throwable =>
        promise failure error
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def dump_pks_elections(electionsSet: scala.collection.immutable.Set[Long]): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      electionsSet.par map ( dump_pks(_) )
      promise success ( () )
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def encodePlaintext(ballot: PlaintextBallot, dto: ElectionDTO): Array[Long] = {
    var array =  Array[Long](ballot.answers.length)
    for (i <- 0 until array.length) {
      val numChars = ( dto.configuration.questions(i).answers.length + 2 ).toString.length
      var strValue : String = ""
      val answer = ballot.answers(i)
      for (j <- 0 until answer.options.length) {
        val optionStrBase = ( answer.options(j) + 1 ).toString
        strValue += "0" * (numChars - optionStrBase.length) + optionStrBase
      }
      array(i) = strValue.toLong
    }
    array
  }

  private def encryptBallots(
    ballotsList: scala.collection.immutable.List[PlaintextBallot],
    electionsSet: scala.collection.immutable.Set[Long],
    electionsInfoMap: scala.collection.mutable.HashMap[Long, ElectionDTO]
  )
    : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future {
      val votes = ballotsList.par.map{ ballot : PlaintextBallot =>
        (ballot.id, encodePlaintext( ballot, electionsInfoMap.get(ballot.id).get ) ) 
      }.seq
      val toEncrypt = {
        val extraSize = vote_count - votes.length
        val extra = Array.fill(extraSize.toInt){ votes(scala.util.Random.nextInt(votes.length)) }
        votes ++ extra
      }
      val writer = new VotesWriter(Paths.get(ciphertexts_path))
      toEncrypt.par.map { case (electionId, plaintext) =>
        val jsonPks = Json.parse(electionsInfoMap.get(electionId).get.pks.get)
        val pks = jsonPks.validate[Array[PublicKey]].get
        val encryptedVote = Crypto.encrypt(pks, plaintext)
        writer.write(electionId, encryptedVote)
      }
      promise success ( () )
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def gen_votes(): Future[Unit] =  {
    val promise = Promise[Unit]()
    Future {
      promise completeWith { parsePlaintexts() flatMap { case (ballotsList, electionsSet) =>
          val dumpPksFuture = dump_pks_elections(electionsSet)
          get_election_info_all(electionsSet) flatMap { case electionsInfoMap =>
            dumpPksFuture flatMap { case pksDumped =>
              encryptBallots(ballotsList, electionsSet, electionsInfoMap)
            }
          }
        }
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  private def send_votes() = {
  }

  def main(args: Array[String]) = {
    if(0 == args.length) {
      showHelp()
    } else {
      parse_args(args)
      val command = args(0)
      if ("gen_votes" == command) {
        gen_votes() onComplete { 
          case Success(value) =>
            println("gen_votes success")
            System.exit(0)
          case Failure(error) =>
            println("gen_votes error " + error)
            System.exit(-1)
        }
      } else if ( "send_votes" == command) {
        send_votes()
      } else {
        showHelp()
      }
    }
  }
}