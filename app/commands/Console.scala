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
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.util.{Success, Failure}

import java.nio.file.{Paths, Files}

case class Answer(options: Array[Int] = Array[Int]())
// id is the election ID
case class PlaintextBallot(id: Int = -1, answers: Array[Answer] = Array[Answer]())
case class PlaintextError(message: String) extends Exception(message)
case class DumpPksError(message: String) extends Exception(message)

object PlaintextBallot {
  val ID = 0
  val ANSWER = 1
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
  implicit val ec = ExecutionContext.fromExecutor(new ForkJoinPool(100))

  var vote_count = 0
  var plaintexts_path = "plaintexts.txt"
  var ciphertexts_path = "ciphertexts.csv"
  var shared_secret = "<password>"
  var datastore = "/home/agoraelections/datastore"
  var batch_size = 50

  private def parse_args(args: Array[String]) = {
    var arg_index = 0
    while (arg_index + 2 < args.length) {
      // number of ballots to create
      if ("--vote-count" == args(arg_index + 1)) {
        vote_count = args(arg_index + 2).toInt
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
      } else if ("--batch-size" == args(arg_index + 1)) {
        batch_size = args(arg_index + 2).toInt
        arg_index += 2
      } else {
        throw new java.lang.IllegalArgumentException("unrecognized argument: " + args(arg_index + 1))
      }
    }
  } 

  private def showHelp() = {
    System.out.println("showHelp")
    ()
  }

  private def dump_pks(electionId: Int): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      promise success ( () )
    }  onComplete  { case Failure(error) =>
      promise failure error
      case _ =>
    }
    promise.future
  }

  private def processPlaintextLine(line: String, lineNumber: Int) : PlaintextBallot  = {
    var strIndex: Option[String] = None
    var state = PlaintextBallot.ID
    var ballot = PlaintextBallot()
    var optionsBuffer: Option[ArrayBuffer[Int]] = None
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
              ballot = PlaintextBallot(strIndex.get.toInt, ballot.answers)
              strIndex = None
              optionsBuffer = Some(ArrayBuffer[Int]())
              state = PlaintextBallot.ANSWER
            case None =>
              throw PlaintextError(s"Error on line $lineNumber, character $i: election index not recognized. Line: $line")
          }
        } else if (PlaintextBallot.ANSWER == state) {
          optionsBuffer match {
            case Some(optionsBufferValue) =>
              if ('|' == c) {
                if (strIndex.isDefined) {
                  optionsBufferValue += strIndex.get.toInt
                  strIndex = None
                }
                answersBuffer += Answer(optionsBufferValue.toArray)
                optionsBuffer = Some(ArrayBuffer[Int]())
              } else if(',' == c) {
                strIndex match {
                  case Some(strIndexValue) =>
                    optionsBufferValue += strIndexValue.toInt
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

  private def parsePlaintexts(): Future[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Int])] = {
    val promise = Promise[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Int])]()
    Future {
      val ballotsList = scala.collection.mutable.ListBuffer[PlaintextBallot]()
      val electionsSet = scala.collection.mutable.Set[Int]()
      if (Files.exists(Paths.get(plaintexts_path))) {
        io.Source.fromFile(plaintexts_path).getLines().zipWithIndex.foreach { 
          case (line, number) =>
              val ballot = processPlaintextLine(line, number)
              ballotsList += ballot
              electionsSet += ballot.id
        }
      } else {
        throw new java.io.FileNotFoundException("tally does not exist")
      }
      promise success ( ballotsList.sortBy(_.id).toList, electionsSet.toSet )
    } onComplete { case Failure(error) =>
      promise failure error
      case _ =>
    }
    promise.future
  }

  private def dump_pks_elections(electionsSet: scala.collection.immutable.Set[Int]): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      var count : Int = 0
      var futuresCreated = Promise[Unit]()
      futuresCreated.future onComplete { 
        case Failure(error) =>
          promise failure error
      case _ =>
      }
      for (electionId <- electionsSet) {
        this.synchronized {
          count += 1
        }
        dump_pks(electionId) onComplete {
          case Success(value) =>
            futuresCreated.future onComplete  { case Success(value2) =>
              this.synchronized {
                if ( 0 >= count ) {
                  promise failure DumpPksError("Logic error")
                }
                count -= 1
                if ( 0 == count ) {
                  promise success ( () )
                }
              }
              case _ =>
            }
          case Failure(error) =>
            futuresCreated failure error
        }
      }
      futuresCreated success ( () )
    } onComplete { case Failure(error) =>
      promise failure error
      case _ =>
    }
    promise.future
  }

  private def encryptBallotTask(ballotsList: scala.collection.immutable.List[PlaintextBallot], index: Int, numBallots: Int, fileWriteMutex: Unit) : Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      promise success ( () )
    } onComplete { case Failure(error) =>
      promise failure error
      case _ =>
    }
    promise.future
  }

  private def encryptBallots(ballotsList: scala.collection.immutable.List[PlaintextBallot], electionsSet: scala.collection.immutable.Set[Int]): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      val fileWriteMutex: Unit = ()
      var count : Int = 0
      while ( count < vote_count ) {
        val numBallots : Int =  if ( vote_count - count < batch_size ) {
          vote_count - count
        } else {
          batch_size
        }
        encryptBallotTask(ballotsList, count % ballotsList.length, numBallots, fileWriteMutex)
        count += numBallots
      }
    } onComplete { case Failure(error) =>
      promise failure error
      case _ =>
    }
    promise.future
  }

  private def gen_votes(): Future[Unit] =  {
    val promise = Promise[Unit]()
    Future {
      promise completeWith { parsePlaintexts() flatMap { case (ballotsList, electionsSet) =>
          dump_pks_elections(electionsSet) flatMap { case u =>
            encryptBallots(ballotsList, electionsSet)
          }
        }
      }
    } onComplete { case Failure(error) =>
      promise failure error
      case _ =>
    }
    promise.future
  }

  private def send_votes() = {
  }

  def main(args: Array[String]) = {
    System.out.println("hi there")
    if(0 == args.length) {
      showHelp()
    } else {
      parse_args(args)
      val command = args(0)
      if ("gen_votes" == command) {
        gen_votes() onSuccess { case a =>
          System.out.println("hi there")
        }
      } else if ( "send_votes" == command) {
        send_votes()
      } else {
        showHelp()
      }
    }
  }
}