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


case class PlaintextError(message: String) extends Exception(message)
case class DumpPksError(message: String) extends Exception(message)
case class BallotEncryptionError(message: String) extends Exception(message)
case class GetElectionInfoError(message: String) extends Exception(message)
case class EncodePlaintextError(message: String) extends Exception(message)
case class EncryptionError(message: String) extends Exception(message)

/**
 * This object contains the states required for reading a plaintext ballot
 * It's used on Console.processPlaintextLine
 */
object PlaintextBallot {
  val ID = 0 // reading election ID
  val ANSWER = 1 // reading answers
}

/**
 * A simple class to write lines to a single file, in a multi-threading safe way
 */
class FileWriter(path: Path) {
  Files.deleteIfExists(path)
  Files.createFile(path)

  def write(content: String) : Future[Unit] = Future {
    this.synchronized {
      Files.write(path, content.getBytes(StandardCharsets.UTF_8), APPEND)
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

  // number of votes to create
  var vote_count : Long = 0
  // hostname of the agora-elections server
  var host = "localhost"
  // agora-elections port
  var port : Long = 9000
  // default plaintext path
  var plaintexts_path = "plaintexts.txt"
  // default ciphertexts path
  var ciphertexts_path = "ciphertexts.csv"
  // default ciphertexts (with khmac) path
  var ciphertexts_khmac_path = "ciphertexts-khmac.csv"
  var shared_secret = "<PASSWORD>"
  // default voter id length (number of characters)
  var voterid_len : Int = 28
  val voterid_alphabet: String = "0123456789abcdef"

  // In order to make http requests with Play without a running Play instance,
  // we have to do this
  // copied from https://www.playframework.com/documentation/2.3.x/ScalaWS
  val clientConfig = new DefaultWSClientConfig()
  val secureDefaults:com.ning.http.client.AsyncHttpClientConfig = new NingAsyncHttpClientConfigBuilder(clientConfig).build()
  val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder(secureDefaults)
  builder.setCompressionEnabled(true)
  val secureDefaultsWithSpecificOptions:com.ning.http.client.AsyncHttpClientConfig = builder.build()
  implicit val wsClient = new play.api.libs.ws.ning.NingWSClient(secureDefaultsWithSpecificOptions)


  /**
   * Parse program arguments
   */
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
      } else if ("--ciphertexts-khmac" == args(arg_index + 1)) {
        ciphertexts_khmac_path = args(arg_index + 2)
        arg_index += 2
      } else if ("--voterid-len" == args(arg_index + 1)) {
        voterid_len = args(arg_index + 2).toInt
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

  /**
   * Prints to the standard output how to use this program
   */
  private def showHelp() = {
    System.out.println(
    """NAME
      |     commands.Console - Generate and send votes for benchmark purposes
      |
      |SYNOPSIS
      |     commands.Console [gen_votes|add_khmacs] [--vote-count number]
      |     [--plaintexts path] [--ciphertexts path] [--ciphertexts-khmac path]
      |     [--voterid-len number] [--shared-secret password] [--port port]
      |
      |DESCRIPTION
      |     In order to run this program, use:
      |         activator "runMain commands.Console [arguments]"
      |     possible commands:
      |     - gen_votes
      |     - add_khmacs
      |     Possible arguments
      |     - vote-count
      |     - plaintexts
      |     - ciphertexts
      |     - ciphertexts-khmac
      |     - voterid-len
      |     - shared-secret
      |     - port
      |""".stripMargin)
  }

 /**
  * Processes a plaintext ballot line
  * A plaintext ballot will have the following format:
  * id|option1,option2|option1||
  *
  * - The id is a number that corresponds to the election id
  * - A separator '|' is used  to signal the end of the election id
  * - The chosen options for each answer come after the election id
  * - The options for an answer are separated by a ','
  * - Each answer is separated by a '|'
  * - A blank vote for an answer is represented with no characters
  * - For example '||' means a blank vote for the corresponding answer
  */
  private def processPlaintextLine(line: String, lineNumber: Long) : PlaintextBallot  = {
    // in this variable we keep adding the characters that will form a complete number
    var strIndex: Option[String] = None
    // state: either reading the election index or the election answers
    var state = PlaintextBallot.ID
    // the ballot to be returned
    var ballot = new PlaintextBallot()
    // for each question in the election, there will be zero (blank vote) to n possible chosen options
    // We'll keep here the options chosen for each answer as we are reading them
    var optionsBuffer: Option[ArrayBuffer[Long]] = None
    // buffer that holds the chosen options for the answers
    var answersBuffer: ArrayBuffer[PlaintextAnswer] = ArrayBuffer[PlaintextAnswer]()

    // iterate through all characters in the string line
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
      }
      // it's not a digit
      else { 
        // state: reading election ID
        if (PlaintextBallot.ID == state) {
          if ('|' != c) {
              throw PlaintextError(s"Error on line $lineNumber, character $i: character separator '|' not found after election index . Line: $line")
          }
          strIndex match {
            case Some(strIndexValue) =>
              ballot = new PlaintextBallot(strIndex.get.toLong, ballot.answers)
              strIndex = None
              optionsBuffer = Some(ArrayBuffer[Long]())
              state = PlaintextBallot.ANSWER
            case None =>
              throw PlaintextError(s"Error on line $lineNumber, character $i: election index not recognized. Line: $line")
          }
        }
        // state: reading answers to each question
        else if (PlaintextBallot.ANSWER == state) {
          optionsBuffer match {
            case Some(optionsBufferValue) =>
              // end of this question, add question to buffer
              if ('|' == c) {
                if (strIndex.isDefined) {
                  optionsBufferValue += strIndex.get.toLong
                  strIndex = None
                }
                answersBuffer += PlaintextAnswer(optionsBufferValue.toArray)
                optionsBuffer = Some(ArrayBuffer[Long]())
              }
              // add chosen option to buffer
              else if(',' == c) {
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
    // add the last answer
    optionsBuffer match {
      case Some(optionsBufferValue) =>
        // read last option of last answer, if there's any
        if (strIndex.isDefined) {
          optionsBufferValue += strIndex.get.toLong
        }
        answersBuffer += PlaintextAnswer(optionsBufferValue.toArray)
      case None =>
        throw PlaintextError(s"Error on line $lineNumber: unknown error, invalid state. Line: $line")
    }
    ballot = new PlaintextBallot(ballot.id, answersBuffer.toArray)
    ballot
  }

  /**
   * Reads a file created by gen_votes and adds a khmac to each ballot
   *
   * The objective of this command is to prepare the ballots to be sent by
   * jmeter.
   *
   * Encrypting the plaintext ballots takes some time, and it can be
   * done at any moment. However, the khmacs will only be valid for a certain
   * period of time, and therefore, it can be useful to first encrypt the
   * ballots and only generate the khmacs just before sending the ballots to
   * the server.
   *
   * The format of each line of the ciphertexts file that this command reads
   * should be:
   *
   *     electionId|voterId|ballot
   *
   * Notice that the separator character is '|'
   *
   * The format of each line of the ciphertexts_khmac file that this command
   * creates is:
   *
   *     electionId|voterId|ballot|khmac
   *
   * Notice that it simply adds the khmac.
   */
  private def add_khmacs() : Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      // check that the file we want to read exists
      if (Files.exists(Paths.get(ciphertexts_path))) {
        val now = Some(System.currentTimeMillis / 1000)
        val writer = new FileWriter(Paths.get(ciphertexts_khmac_path))
        promise completeWith {
          Future.traverse (io.Source.fromFile(ciphertexts_path).getLines()) { file_line =>
            val array = file_line.split('|')
            val eid = array(0).toLong
            val voterId = array(1)
            val khmac = get_khmac(voterId, "AuthEvent", eid, "vote", now)
            writer.write(file_line + "|" + khmac) 
          } map { _ =>
            ()
          }
        }
      } else {
        throw new java.io.FileNotFoundException("tally does not exist")
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  /**
   * Opens the plaintexts file, and reads and parses each line.
   * See Console.processPlaintextLine() comments for more info on the format
   * of the plaintext file.
   */
  private def parsePlaintexts(): Future[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Long])] = {
    val promise = Promise[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Long])]()
    Future {
      val path = Paths.get(plaintexts_path)
      // Check that the plaintexts file exists
      if (Files.exists(path)) {
        // buffer with all the parsed ballots
        val ballotsList = scala.collection.mutable.ListBuffer[PlaintextBallot]()
        // set of all the election ids
        val electionsSet = scala.collection.mutable.LinkedHashSet[Long]()
        // read all lines
        io.Source.fromFile(plaintexts_path).getLines().zipWithIndex.foreach { 
          case (line, number) =>
              // parse line
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
        throw new java.io.FileNotFoundException(s"plaintext file ${path.toAbsolutePath.toString} does not exist or can't be opened")
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  /**
   * Generate khmac
   */
  private def get_khmac(userId: String, objType: String, objId: Long, perm: String, nowOpt: Option[Long] = None) : String = {
    val now: Long = nowOpt match {
      case Some(time) => time
      case None => System.currentTimeMillis / 1000
    }
    val message = s"$userId:$objType:$objId:$perm:$now"
    val hmac = Crypto.hmac(shared_secret, message)
    val khmac = s"khmac:///sha-256;$hmac/$message"
    khmac
  }

  /**
   * Makes an http request to agora-elections to get the election info.
   * Returns the parsed election info
   */
  private def get_election_info(electionId: Long) : Future[ElectionDTO] = {
    val promise = Promise[ElectionDTO]
    Future {
      val url = s"http://$host:$port/api/election/$electionId"
      wsClient.url(url) .get() map { response =>
        if(response.status == HTTP.OK) {
          val dto = (response.json \ "payload").validate[ElectionDTO].get
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

  /**
   * Given a set of election ids, it returns a map of the election ids and their
   * election info.
   */
  private def get_election_info_all(electionsSet: scala.collection.immutable.Set[Long]) : Future[scala.collection.mutable.HashMap[Long, ElectionDTO]] = {
    val promise = Promise[scala.collection.mutable.HashMap[Long, ElectionDTO]]()
    Future {
      val map = scala.collection.mutable.HashMap[Long, ElectionDTO]()
      promise completeWith {
        Future.traverse(electionsSet) { eid =>
          get_election_info(eid) map { dto : ElectionDTO =>
            // using synchronized to make it thread-safe
            this.synchronized {
              map += (dto.id -> dto)
            }
          }
        } map { _ =>
          map
        }
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  /**
   * Makes an HTTP request to agora-elections to dump the public keys for a
   * given election id.
   */
  private def dump_pks(electionId: Long): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      val auth = get_khmac("", "AuthEvent", electionId, "edit")
      val url = s"http://$host:$port/api/election/$electionId/dump-pks"
      wsClient.url(url)
        .withHeaders("Authorization" -> auth)
        .post(Results.EmptyContent()).map { response =>
          if(response.status == HTTP.OK) {
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

  /**
   * Given a set of election ids, it returns a future that will be completed
   * when all the public keys of all those elections have been dumped.
   */
  private def dump_pks_elections(electionsSet: scala.collection.immutable.Set[Long]): Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      promise completeWith {
        Future.traverse( electionsSet ) ( x => dump_pks(x) ) map { _ =>
          ()
        }
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  /**
   * Given a plaintext and the election info, it encodes each question's answers
   * into a number, ready to be encrypted
   */
  private def encodePlaintext(ballot: PlaintextBallot, dto: ElectionDTO): Array[Long] = {
    var array =  new Array[Long](ballot.answers.length)
    if (dto.configuration.questions.length != ballot.answers.length) {
      val strBallot = Json.toJson(ballot).toString
      val strDto = Json.toJson(dto).toString
      throw EncodePlaintextError(
        s"""Plaintext ballot:\n$strBallot\nElection dto:\n$strDto\n
           |Error: wrong number of questions on the plaintext ballot:
           |${dto.configuration.questions.length} != ${ballot.answers.length}""")
    }
    for (i <- 0 until array.length) {
      Try {
        val numChars = ( dto.configuration.questions(i).answers.length + 2 ).toString.length
        // holds the value of the encoded answer, before converting it to a Long
        var strValue : String = ""
        val answer = ballot.answers(i)
        for (j <- 0 until answer.options.length) {
          // sum 1 as the encryption method can't encode value zero
          val optionStrBase = ( answer.options(j) + 1 ).toString
          // Each chosen option needs to have the same length in number of 
          // characters/digits, so we fill in with zeros on the left
          strValue += "0" * (numChars - optionStrBase.length) + optionStrBase
        }
        // blank vote
        if (0 == answer.options.length) {
          val optionStrBase = ( dto.configuration.questions(i).answers.length + 2 ).toString
          strValue += "0" * (numChars - optionStrBase.length) + optionStrBase
        }
        // Convert to long. Notice that the zeros on the left added by the last
        // chosen option won't be included
        array(i) = strValue.toLong
      } match {
        case Failure(error) =>
          val strBallot = Json.toJson(ballot).toString
          val strDto = Json.toJson(dto).toString
          throw EncodePlaintextError(s"Plaintext ballot:\n$strBallot\nElection dto:\n$strDto\nQuestion index where error was generated: $i\nError: ${error.getMessage}")
        case _ =>
      }
    }
    array
  }

  /**
   * Generate a random string with a given length and alphabet
   * Note: This is not truly random, it uses a pseudo-random generator
   */
  private def gen_rnd_str(len: Int, choices: String) : String = {
    (0 until len) map { _ =>
      choices( scala.util.Random.nextInt(choices.length) )
    } mkString
  }

  /**
  * Generate a random voter id
   */
  private def generate_voterid() : String = {
    gen_rnd_str(voterid_len, voterid_alphabet)
  }

  /**
   * Given an election id and an encrypted ballot, it generates a String text
   * line in the following format:
   *     electionId|voterId|vote
   * Notice that it automatically adds a random voter id
   */
  private def generate_vote_line(id: Long, ballot: EncryptedVote) : String = {
    val vote = Json.toJson(ballot).toString
    val voteHash = Crypto.sha256(vote)
    val voterId = generate_voterid()
    val voteDTO = Json.toJson(VoteDTO(vote, voteHash)).toString
    val line = id.toString + "|" + generate_voterid() + "|" + voteDTO + "\n"
    line
  }

  /**
   * Given a map of election ids and election info, it returns a map with the
   * election public keys
   */
  private def get_pks_map(electionsInfoMap: scala.collection.mutable.HashMap[Long, ElectionDTO])
  : scala.collection.mutable.HashMap[Long, Array[PublicKey]] = 
  {
    val pksMap = scala.collection.mutable.HashMap[Long, Array[PublicKey]]()
    // map election ids and public keys
    electionsInfoMap foreach { case (key, value) =>
       val strPks = value.pks match {
         case Some(strPks) =>
           strPks
         case None =>
           val strDto = Json.toJson(value).toString
           throw new GetElectionInfoError(s"Error: no public keys found for election $key. Election Info: $strDto")
       }
       val jsonPks = Try {
         Json.parse(strPks)
       } match {
         case Success(jsonPks) =>
          jsonPks
         case Failure(error) =>
          throw new GetElectionInfoError(s"Error: public keys with invalid JSON format for election $key.\nPublic keys: $strPks.\n${error.getMessage}")
       }
       jsonPks.validate[Array[PublicKey]] match {
         case s :JsSuccess[Array[PublicKey]] =>
           val pks = s.get
           pksMap += (key -> pks)
         case error : JsError =>
           throw new GetElectionInfoError(s"Error: public keys with invalid Array[PublicKey] format for election $key.\nPublic keys ${jsonPks.toString}\n")
       }
    }
    pksMap
  }

  /**
   * Given a list of plaintext ballots and a map of election ids and their
   * election info, it generates and encrypts the ballots, saving them to a
   * file.
   * Read generate_vote_line for more info on the output file format.
   */
  private def encryptBallots(
    ballotsList: scala.collection.immutable.List[PlaintextBallot],
    electionsInfoMap: scala.collection.mutable.HashMap[Long, ElectionDTO]
  )
    : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future {
      val pksMap = get_pks_map(electionsInfoMap)
      // base list of encoded plaintext ballots
      val votes = ballotsList.par.map{ ballot : PlaintextBallot =>
        (ballot.id, encodePlaintext( ballot, electionsInfoMap.get(ballot.id).get ) ) 
      }.seq
      // we need to generate vote_count encrypted ballots, fill the list with
      // random samples of the base list
      val toEncrypt : Seq[(Long, Array[Long])] = {
        val extraSize = vote_count - votes.length
        val extra = Array.fill(extraSize.toInt){ votes(scala.util.Random.nextInt(votes.length)) }
        votes ++ extra
      }
      val writer = new FileWriter(Paths.get(ciphertexts_path))
      promise completeWith {
        Future.traverse (toEncrypt) { case (electionId, plaintext) =>
          val writePromise = Promise[Unit]()
          Future {
            val pks = pksMap.get(electionId).get
            if (pks.length != plaintext.length) {
              throw new EncryptionError(s"${pks.length} != ${plaintext.length}")
            }
            val encryptedVote = Crypto.encrypt(pks, plaintext)
            val line = generate_vote_line(electionId, encryptedVote)
            writePromise completeWith writer.write(line)
          } recover { case error: Throwable =>
            writePromise failure error
          }
          writePromise.future
        } map { _ =>
          ()
        }
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  /**
   * Given a list of plaintexts, it generates their ciphertexts
   */
  private def gen_votes(): Future[Unit] =  {
    val promise = Promise[Unit]()
    Future {
      promise completeWith {
        parsePlaintexts() flatMap {
          case (ballotsList, electionsSet) =>
            val dumpPksFuture = dump_pks_elections(electionsSet)
            get_election_info_all(electionsSet) flatMap {
              electionsInfoMap =>
                dumpPksFuture flatMap {
                  pksDumped =>
                    encryptBallots(ballotsList, electionsInfoMap)
                }
            }
        }
      }
    } recover { case error: Throwable =>
      promise failure error
    }
    promise.future
  }

  def main(args: Array[String]) : Unit = {
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
      } else if ( "add_khmacs" == command) {
        add_khmacs()
      } else {
        showHelp()
      }
    }
    ()
  }
}