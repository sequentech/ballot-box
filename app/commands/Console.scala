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

import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import scala.math.BigInt
import java.security.KeyStore
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
case class ElectionIdsFileError(message: String) extends Exception(message)

/**
 * This object contains the states required for reading a plaintext ballot
 * It's used on Console.processPlaintextLine
 */
object PlaintextBallot
{
  val ID = 0 // reading election ID
  val ANSWER = 1 // reading answers
}

/**
 * A simple class to write lines to a single file, in a multi-threading safe way
 */
class FileWriter(path: Path)
{
  Files.deleteIfExists(path)
  Files.createFile(path)

  def write(content: String) : Future[Unit] = Future
  {
    this.synchronized
    {
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
object Console
{
  //implicit val ec = ExecutionContext.fromExecutor(new ForkJoinPool(100))
  //implicit val slickExecutionContext = Akka.system.dispatchers.lookup("play.akka.actor.slick-context")

  // number of votes to create
  var vote_count : Long = 0
  // http or https
  var http_type = "https"
  // hostname of the agora-elections server
  var host = "localhost"
  // path to the service inside the host
  var service_path = "elections/"
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
  var keystore_path: Option[String] = None
  var election_ids_path : String = "election_ids.txt"

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
  private def parse_args(args: Array[String]) =
  {
    var arg_index = 0
    while (arg_index + 2 < args.length)
    {
      // number of ballots to create
      if ("--vote-count" == args(arg_index + 1))
      {
        vote_count = args(arg_index + 2).toLong
        arg_index += 2
      }
      else if ("--plaintexts" == args(arg_index + 1))
      {
        plaintexts_path = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--ciphertexts" == args(arg_index + 1))
      {
        ciphertexts_path = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--ciphertexts-khmac" == args(arg_index + 1))
      {
        ciphertexts_khmac_path = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--voterid-len" == args(arg_index + 1))
      {
        voterid_len = args(arg_index + 2).toInt
        arg_index += 2
      }
      else if ("--ssl" == args(arg_index + 1))
      {
        if ("true" == args(arg_index + 2))
        {
          http_type = "https"
        }
        else if ("false" == args(arg_index + 2))
        {
          http_type = "http"
        }
        else
        {
          throw new java.lang.IllegalArgumentException(s"Invalid --ssl option: " + args(arg_index + 2) + ". Valid values: true, false")
        }
        arg_index += 2
      }
      else if ("--shared-secret" == args(arg_index + 1))
      {
        shared_secret = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--service-path" == args(arg_index + 1))
      {
        service_path = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--election-ids" == args(arg_index + 1))
      {
        election_ids_path = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--host" == args(arg_index + 1))
      {
        host = args(arg_index + 2)
        arg_index += 2
      }
      else if ("--port" == args(arg_index + 1))
      {
        port = args(arg_index + 2).toLong
        if (port <= 0 || port > 65535)
        {
          throw new java.lang.IllegalArgumentException(s"Invalid port $port")
        }
        arg_index += 2
      }
      else
      {
        throw new java.lang.IllegalArgumentException("Unrecognized argument: " +
          args(arg_index + 1))
      }
    }
  }

  /**
   * Prints to the standard output how to use this program
   */
  private def showHelp() =
  {
    System.out.println(
"""NAME
  |     commands.Console - Generate and send votes for load testing and benchmarking
  |
  |SYNOPSIS
  |     commands.Console [gen_plaintexts|gen_votes|add_khmacs]
  |     [--vote-count number] [--plaintexts path] [--ciphertexts path]
  |     [--ciphertexts-khmac path] [--voterid-len number]
  |     [--shared-secret password] [--port port] [--ssl boolean]
  |     [--host hostname] [--service-path path] [--election-ids path]
  |
  |DESCRIPTION
  |     In order to run this program, use:
  |         activator "runMain commands.Console [arguments]"
  |
  |     Possible commands
  |
  |     gen_plaintexts
  |       Generates plaintext ballots covering every option for every question
  |       for each election id. Use --vote-count to set the number of plaintexts
  |       to be generated, which should be at least the number of elections. 
  |
  |     gen_votes
  |       Given a plaintext file where each string line contains a plaintext,
  |       it generates a ciphertext file with encrypted ballots. Encrypting
  |       ballots is a computationally intensive process, so it can take a
  |       while. The user ids are generated in a random fashion.
  |
  |     add_khmacs
  |       Given a ciphertexts file where each string line contains an encrypted
  |       ballot, it adds a khmac to each line and saves the new file. This step
  |       is faster than encrypting the ballots and it prepares them to be sent
  |       to the ballot box with jmeter.The khmacs are only valid for a period
  |       of time and that's why they are generated just before sending the
  |       ballots.
  |
  |     Possible arguments
  |
  |     --vote-count
  |       Number of ballots to be generated. If the number of plaintexts is less
  |       than the vote count, then the plaintexts will be repeated till the
  |       desired number is achieved.
  |       Default value: 0
  |
  |     --plaintexts
  |       Path to the plaintexts file. In this file, each line represents a
  |       ballot with the chosen voting options. For example:
  |           39||0|22,4
  |       In this line, the election id is 39, on the first question the chosen
  |       option is a blank vote, on the first option it's voting to option 0,
  |       and on the last question it's voting to options 22 and 4.
  |       Notice that as each line indicates the election id, this file can be
  |       used to generate ballots for multiple elections.
  |       Default value: plaintexts.txt
  |
  |     --ciphertexts
  |       The path to the ciphertexts file. This file is created by the
  |       gen_votes command and it contains the encrypted ballots generated from
  |       the plaintexts file. In this file, each line represents an encrypted
  |       ballot, in the following format:
  |           election_id|voter_id|ballot
  |       This is an intermediate file, because as it doesn't contain the
  |       khmacs, it's not ready to be used by jmeter.
  |       Default value: ciphertexts.csv
  |
  |     --ciphertexts-khmac
  |       The path to the final ciphertexts file, which includes the khmacs.
  |       This file is generated by the add_khmacs command. The file format is
  |       the same as the ciphertexts file, except that it adds the khmac to
  |       each ballot:
  |           election_id|voter_id|ballot|khmac
  |       This is the file that will be fed to jmeter
  |       Default value: ciphertexts-khmac.csv
  |
  |     --voterid-len
  |       Voter ids for each ballot are generated with random numbers and
  |       represented in hexadecimal format. This parameter configures the
  |       number of hexadecimal characters of the voter ids.
  |       Default value: 28
  |
  |     --shared-secret
  |       The shared secret required for authentication and creating khmacs.
  |       This value can be found on the config.yml
  |       agora.agora_elections.shared_secret variable.
  |       Default value: <PASSWORD>
  |
  |     --service-path
  |       The location of service agora_elections in the host server. For
  |       example if a call to the server is:
  |           https://agora:443/elections/api/election/39
  |       Then the service path is "elections/".
  |       Default value: elections/
  |
  |     --port
  |       Port number of the agora_elections service.
  |       Default value: 443
  |
  |     --host
  |       Hostname of the agora_elections service.
  |       Default value: localhost
  |
  |     --ssl
  |       Enables or disables https. If true, http requests to the
  |       agora_elections service will use https.
  |       Usually, the agora_elections service will use a self-signed
  |       certificate. To make this program to accept the certificate, use
  |       -Djavax.net.ssl.trustStore=/path/to/jks/truststore  and 
  |       -Djavax.net.ssl.trustStorePassword=pass with a jks truststore that
  |       includes the agora_elections certificate.
  |       Possible values: true|false
  |       Default value: true
  |
  |     --election-ids path
  |       Path to a file containing the election ids. Required parameter by
  |       command gen_plaintexts.
  |
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
  private def processPlaintextLine(
    line: String,
    lineNumber: Long)
    : PlaintextBallot  =
  {
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
    for (i <- 0 until line.length)
    {
      val c = line.charAt(i)
      // keep reading digits till we get the whole number
      if(c.isDigit)
      {
        strIndex match
        {
          // add a character to the string containing a number
          case Some(strIndexValue) =>
            strIndex = Some(strIndexValue + c.toString)
          case None => ()
            // add the first character to the string containing a number
            strIndex = Some(c.toString)
        }
      }
      // it's not a digit
      else
      {
        // state: reading election ID
        if (PlaintextBallot.ID == state)
        {
          if ('|' != c)
          {
              throw PlaintextError(s"Error on line $lineNumber, character $i: character separator '|' not found after election index . Line: $line")
          }
          strIndex match
          {
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
        else if (PlaintextBallot.ANSWER == state)
        {
          optionsBuffer match
          {
            case Some(optionsBufferValue) =>
              // end of this question, add question to buffer
              if ('|' == c)
              {
                if (strIndex.isDefined)
                {
                  optionsBufferValue += strIndex.get.toLong
                  strIndex = None
                }
                answersBuffer += PlaintextAnswer(optionsBufferValue.toArray)
                optionsBuffer = Some(ArrayBuffer[Long]())
              }
              // add chosen option to buffer
              else if(',' == c)
              {
                strIndex match
                {
                  case Some(strIndexValue) =>
                    optionsBufferValue += strIndexValue.toLong
                    strIndex = None
                  case None =>
                    throw PlaintextError(s"Error on line $lineNumber, character $i: option number not recognized before comma on question ${ballot.answers.length}. Line: $line")
                }
              }
              else
              {
                throw PlaintextError(s"Error on line $lineNumber, character $i: invalid character: $c. Line: $line")
              }
            case None =>
              PlaintextError(s"Error on line $lineNumber, character $i: unknown error, invalid state. Line: $line")
          }
        }
      }
    }
    // add the last answer
    optionsBuffer match
    {
      case Some(optionsBufferValue) =>
        // read last option of last answer, if there's any
        if (strIndex.isDefined)
        {
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
  private def add_khmacs() : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future
    {
      // check that the file we want to read exists
      if (Files.exists(Paths.get(ciphertexts_path)))
      {
        val now = Some(System.currentTimeMillis / 1000)
        val writer = new FileWriter(Paths.get(ciphertexts_khmac_path))
        promise completeWith
        {
          Future.traverse (io.Source.fromFile(ciphertexts_path).getLines())
          {
            file_line =>
              val array = file_line.split('|')
              val eid = array(0).toLong
              val voterId = array(1)
              val khmac = get_khmac(voterId, "AuthEvent", eid, "vote", now)
              writer.write(file_line + "|" + khmac + "\n") 
          }
          .map
          {
            _ => ()
          }
        }
      }
      else
      {
        throw new java.io.FileNotFoundException("tally does not exist")
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Opens the plaintexts file, and reads and parses each line.
   * See Console.processPlaintextLine() comments for more info on the format
   * of the plaintext file.
   */
  private def parsePlaintexts()
    : Future[
             (scala.collection.immutable.List[PlaintextBallot],
             scala.collection.immutable.Set[Long])] =
  {
    val promise = Promise[(scala.collection.immutable.List[PlaintextBallot], scala.collection.immutable.Set[Long])]()
    Future
    {
      val path = Paths.get(plaintexts_path)
      // Check that the plaintexts file exists
      if (Files.exists(path))
      {
        // buffer with all the parsed ballots
        val ballotsList = scala.collection.mutable.ListBuffer[PlaintextBallot]()
        // set of all the election ids
        val electionsSet = scala.collection.mutable.LinkedHashSet[Long]()
        // read all lines
        io.Source.fromFile(plaintexts_path).getLines().zipWithIndex.foreach
        {
          case (line, number) =>
              // parse line
              val ballot = processPlaintextLine(line, number)
              ballotsList += ballot
              electionsSet += ballot.id
        }
        if ( electionsSet.isEmpty || ballotsList.isEmpty )
        {
          throw PlaintextError("Error: no ballot found")
        }
        else
        {
          promise success ( ( ballotsList.sortBy(_.id).toList, electionsSet.toSet ) )
        }
      }
      else
      {
        throw new java.io.FileNotFoundException(s"plaintext file ${path.toAbsolutePath.toString} does not exist or can't be opened")
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Generate khmac
   */
  private def get_khmac(
    userId: String,
    objType: String,
    objId: Long,
    perm: String,
    nowOpt: Option[Long] = None
  )
    : String =
  {
    val now: Long = nowOpt match
    {
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
  private def get_election_info(electionId: Long) : Future[ElectionDTO] =
  {
    val promise = Promise[ElectionDTO]
    Future
    {
      val url = s"$http_type://$host:$port/${service_path}api/election/$electionId"
      wsClient.url(url) .get() .map
      {
        response =>
          if(response.status == HTTP.OK)
          {
            val dto = (response.json \ "payload").validate[ElectionDTO].get
            promise success dto
          }
          else
          {
            promise failure GetElectionInfoError(
              s"HTTP GET request to $url returned status: ${response.status} and body: ${response.body}")
          }
      }
      .recover
      {
        case error: Throwable => promise failure error
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Given a set of election ids, it returns a map of the election ids and their
   * election info.
   */
  private def get_election_info_all(
    electionsSet: scala.collection.immutable.Set[Long]
  )
    : Future[scala.collection.mutable.HashMap[Long, ElectionDTO]] =
    {
    val promise = Promise[scala.collection.mutable.HashMap[Long, ElectionDTO]]()
    Future
    {
      val map = scala.collection.mutable.HashMap[Long, ElectionDTO]()
      promise completeWith
      {
        Future.traverse(electionsSet)
        {
          eid =>
            get_election_info(eid) map
            {
              dto : ElectionDTO =>
                // using synchronized to make it thread-safe
                this.synchronized
                {
                  map += (dto.id -> dto)
                }
            }
        }
        .map
        {
          _ => map
        }
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Makes an HTTP request to agora-elections to dump the public keys for a
   * given election id.
   */
  private def dump_pks(electionId: Long): Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future
    {
      val auth = get_khmac("", "AuthEvent", electionId, "edit")
      val url = s"$http_type://$host:$port/${service_path}api/election/$electionId/dump-pks"
      wsClient.url(url)
        .withHeaders("Authorization" -> auth)
        .post(Results.EmptyContent()).map
        {
          response =>
            if(response.status == HTTP.OK)
            {
              promise success ( () )
            }
            else
            {
              promise failure DumpPksError(s"HTTP POST request to $url returned status: ${response.status} and body: ${response.body}")
            }
      }
      .recover
      {
        case error: Throwable => promise failure error
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Given a set of election ids, it returns a future that will be completed
   * when all the public keys of all those elections have been dumped.
   */
  private def dump_pks_elections(
    electionsSet: scala.collection.immutable.Set[Long]
  )
    : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future
    {
      promise completeWith
      {
        Future.traverse( electionsSet ) ( x => dump_pks(x) ) map
        {
          _ => ()
        }
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Given a plaintext and the election info, it encodes each question's answers
   * into a number, ready to be encrypted
   */
  private def encodePlaintext(
    ballot: PlaintextBallot,
    dto: ElectionDTO
  )
    : Array[BigInt] =
  {
    var array =  new Array[BigInt](ballot.answers.length)
    if (dto.configuration.questions.length != ballot.answers.length)
    {
      val strBallot = Json.toJson(ballot).toString
      val strDto = Json.toJson(dto).toString
      throw EncodePlaintextError(
        s"""Plaintext ballot:\n$strBallot\nElection dto:\n$strDto\n
           |Error: wrong number of questions on the plaintext ballot:
           |${dto.configuration.questions.length} != ${ballot.answers.length}""")
    }
    for (i <- 0 until array.length)
    {
      Try
      {
        val numChars = ( dto.configuration.questions(i).answers.length + 2 ).toString.length
        // holds the value of the encoded answer, before converting it to a Long
        var strValue : String = ""
        val answer = ballot.answers(i)
        for (j <- 0 until answer.options.length)
        {
          // sum 1 as it would cause encoding problems, as zeros on the left are
          // removed in the end
          val optionStrBase = ( answer.options(j) + 1 ).toString
          // Each chosen option needs to have the same length in number of 
          // characters/digits, so we fill in with zeros on the left
          strValue += "0" * (numChars - optionStrBase.length) + optionStrBase
        }
        // blank vote
        if (0 == answer.options.length)
        {
          val optionStrBase = ( dto.configuration.questions(i).answers.length + 2 ).toString
          strValue += "0" * (numChars - optionStrBase.length) + optionStrBase
        }
        // Convert to long. Notice that the zeros on the left added by the last
        // chosen option won't be included
        array(i) = BigInt(strValue)
      }
      match
      {
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
  private def gen_rnd_str(len: Int, choices: String) : String =
  {
    (0 until len) map
    {
      _ => choices( scala.util.Random.nextInt(choices.length) )
    } mkString
  }

  /**
  * Generate a random voter id
   */
  private def generate_voterid() : String =
  {
    gen_rnd_str(voterid_len, voterid_alphabet)
  }

  /**
   * Given an election id and an encrypted ballot, it generates a String text
   * line in the following format:
   *     electionId|voterId|vote
   * Notice that it automatically adds a random voter id
   */
  private def generate_vote_line(id: Long, ballot: EncryptedVote) : String =
  {
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
  private def get_pks_map(
    electionsInfoMap: scala.collection.mutable.HashMap[Long, ElectionDTO]
  )
    : scala.collection.mutable.HashMap[Long, Array[PublicKey]] = 
  {
    val pksMap = scala.collection.mutable.HashMap[Long, Array[PublicKey]]()
    // map election ids and public keys
    electionsInfoMap foreach
    {
      case (key, value) =>
        val strPks = value.pks match
        {
          case Some(strPks) =>
            strPks
          case None =>
            val strDto = Json.toJson(value).toString
            throw new GetElectionInfoError(s"Error: no public keys found for election $key. Election Info: $strDto")
        }
        val jsonPks = Try
        {
          Json.parse(strPks)
        }
        match
        {
          case Success(jsonPks) =>
           jsonPks
          case Failure(error) =>
           throw new GetElectionInfoError(s"Error: public keys with invalid JSON format for election $key.\nPublic keys: $strPks.\n${error.getMessage}")
        }
        jsonPks.validate[Array[PublicKey]] match
        {
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
    Future
    {
      val pksMap = get_pks_map(electionsInfoMap)
      // base list of encoded plaintext ballots
      val votes = ballotsList.par.map
      {
        ballot : PlaintextBallot =>
          (ballot.id, encodePlaintext( ballot, electionsInfoMap.get(ballot.id).get ) )
      }.seq
      val writer = new FileWriter(Paths.get(ciphertexts_path))
      // we need to generate vote_count encrypted ballots, iterate the
      // plaintexts list as many times as we need
      val futuresArray =
      (0 until vote_count.toInt).toStream.map
      {
        index =>
          val writePromise = Promise[Unit]()
          Future
          {
            val (electionId, plaintext) = votes(index % votes.size)
            val pks = pksMap.get(electionId).get
            if (pks.length != plaintext.length)
            {
              throw new EncryptionError(s"${pks.length} != ${plaintext.length}")
            }
            val encryptedVote = Crypto.encryptBig(pks, plaintext)
            val line = generate_vote_line(electionId, encryptedVote)
            writePromise completeWith writer.write(line)
          }
          .recover
          {
            case error: Throwable => writePromise failure error
          }
          writePromise.future
      }
      promise completeWith
      {
        Future.sequence (futuresArray)
        .map
        {
          _ => ()
        }
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Given a list of plaintexts, it generates their ciphertexts
   */
  private def gen_votes() : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future
    {
      promise completeWith
      {
        parsePlaintexts() flatMap
        {
          case (ballotsList, electionsSet) =>
            val dumpPksFuture = dump_pks_elections(electionsSet)
            get_election_info_all(electionsSet) flatMap
            {
              electionsInfoMap =>
                dumpPksFuture flatMap
                {
                  pksDumped =>
                    encryptBallots(ballotsList, electionsInfoMap)
                }
            }
        }
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Generates 'numVotes' random plaintext ballots for election id 'eid', using
   * election info 'dto'.
   * Each plaintext ballot will fill a string line and it will have the format
   * described by method @processPlaintextLine:
   *    eid||option1,option2|option1||
   */

  private def generate_plaintexts_for_eid(
    eid: Long,
    numVotes: Long,
    dto: ElectionDTO
  )
    : String =
  {
    // output variable
    var outText: String = ""
    // election id, as a string
    val sEid = eid.toString
    // iterate over all the plaintext ballots that need be generated
    for (i <- 0L until numVotes)
    {
      // variable that contains one plaintext ballot
      var line: String = sEid
      // iterate over all the questions this election has
      for (question <- dto.configuration.questions)
      {
        // add questions separator
        line += "|"
        val diff = question.max - question.min
        // buffer with all possible options for this question
        val optionsBuffer : scala.collection.mutable.ArrayBuffer[Long] =
          (0L until question.answers.size.toLong)
          .to[scala.collection.mutable.ArrayBuffer]
        val num_answers = question.min + scala.util.Random.nextInt(diff + 1)
        // iterate over the number of answers to be generated for this question
        for (j <- 0 until num_answers)
        {
          // add options separator
          if (0 != j)
          {
            line += ","
          }
          // select a random, non-repeated option
          val option = optionsBuffer(scala.util.Random.nextInt(optionsBuffer.size))
          // remove this option for the next operation, to avoid options repetition
          optionsBuffer -= option
          // add option to plaintext ballot line
          line += option.toString
        }
      }
      // add plaintext ballot line to output variable
      outText += line + "\n"
    }
    outText
  }

  /**
   * Given a map election id -> election info, and the total number of
   * plaintext ballots (vote_count) to be generated, it generates another map
   * election id -> (votes, election info).
   * We'll generate between a and a+1 plaintext ballots for each election id,
   * where a = vote_count / num elections.
   */

  private def generate_map_num_votes_dto(
    electionsInfoMap: scala.collection.mutable.HashMap[Long, ElectionDTO]
  )
    : scala.collection.mutable.HashMap[Long, (Long, ElectionDTO)]
  =
  {
    val numElections = electionsInfoMap.size
    if (0 == vote_count)
    {
      throw new java.lang.IllegalArgumentException(s"Missing argument: --vote-count")
    }
    else if (vote_count < numElections)
    {
      throw new java.lang.IllegalArgumentException(
       s"vote-count: $vote_count is less than the number of elections ($numElections)")
    }
    // minimum number of votes per election
    val votesFloor = (vote_count.toFloat / numElections.toFloat).floor.toLong
    // number of votes already "used"
    var votesSoFar: Long = 0
    // number of elections already used
    var electionsSoFar: Long = 0
    // the output variable
    val electionsVotesDtoMap = scala.collection.mutable.HashMap[Long, (Long, ElectionDTO)]()
    //
    electionsInfoMap.foreach
    {
      case (eid, dto) =>
        // votes to be generated for this election
        val votesToGenThisEid = if (vote_count - votesSoFar > votesFloor*(numElections - electionsSoFar))
        {
          votesFloor + 1
        }
        else
        {
          votesFloor
        }
        // add element to map
        electionsVotesDtoMap += (eid -> (votesToGenThisEid, dto))
        votesSoFar += votesToGenThisEid
        electionsSoFar += 1
    }
    electionsVotesDtoMap
  }

  /**
   * Given a map of election id to election info, it generates --vote-count
   * number of plaintext ballots and save them on --plaintexts
   */

  private def generate_save_plaintexts(
    electionsInfoMap: scala.collection.mutable.HashMap[Long, ElectionDTO]
  )
    : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future
    {
      // get number of votes per election id, and election info, in a map
      val electionsVotesDtoMap = generate_map_num_votes_dto(electionsInfoMap)
      // writer to write into the output plaintexts file in a thread-safe way
      val writer = new FileWriter(Paths.get(plaintexts_path))
      promise completeWith {
        Future.traverse (electionsVotesDtoMap)
        {
          case (eid, (numVotes, dto)) =>
            val writePromise = Promise[Unit]()
            Future
            {
              // generate plaintexts for this election
              val text = generate_plaintexts_for_eid(eid, numVotes, dto)
              // write plaintexts for this election into output file
              writePromise completeWith writer.write(text)
            }
            .recover
            {
              case error: Throwable => writePromise failure error
            }
            writePromise.future
        }.map
        {
          _ => ()
        }
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Reads the --election-ids file, which consists of an election id per line,
   * and returns a set of election ids
   */

  private def getElectionsSet()
    : Future[scala.collection.immutable.Set[Long]] =
  {
    val promise = Promise[scala.collection.immutable.Set[Long]]()
    Future
    {
      // convert string to path
      val eids_path = Paths.get(election_ids_path)
      // read file, split it into string lines
      val electionsSet =
        io.Source.fromFile(election_ids_path)
        .getLines()
        // convert each line to an integer representing the election id
        .toList.map
        {
          strEid => strEid.toLong
        }
        .toSet
      promise.success(electionsSet)
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  /**
   * Generates random plaintext ballots for a number of elections.
   * It generates --vote-count number of plaintexts, for the elections mentioned
   * on file --election-ids. The election-ids file should have an election id on
   * each line. Election ids should not be repeated. The generated plaintexts
   * will be saved on the file set by option --plaintexts (or its default 
   * value). The output format of the plaintexts file is compatible with the
   * required input for command gen_votes. The number of votes should be at
   * least as big as the number of elections.
   */
  private def gen_plaintexts()
    : Future[Unit] =
  {
    val promise = Promise[Unit]()
    Future
    {
      promise completeWith
      {
        getElectionsSet()
        .flatMap
        {
          electionsSet => get_election_info_all(electionsSet) 
        }
        .flatMap
        {
          electionsInfoMap => generate_save_plaintexts(electionsInfoMap)
        }
      }
    }
    .recover
    {
      case error: Throwable => promise failure error
    }
    promise.future
  }

  def main(args: Array[String])
    : Unit =
  {
    if(0 == args.length)
    {
      showHelp()
    } else 
    {
      parse_args(args)
      val command = args(0)
      if ("gen_votes" == command)
      {
        gen_votes() onComplete
        {
          case Success(value) =>
            println("gen_votes success")
            System.exit(0)
          case Failure(error) =>
            println("gen_votes error " + error)
            System.exit(-1)
        }
      }
      else if ("add_khmacs" == command)
      {
        add_khmacs() onComplete
        {
          case Success(value) =>
            println("add_khmacs success")
            System.exit(0)
          case Failure(error) =>
            println("add_khmacs error " + error)
            System.exit(-1)
        }
      }
      else if ("gen_plaintexts" == command)
      {
        gen_plaintexts() onComplete
        {
          case Success(value) =>
            println("gen_plaintexts success")
            System.exit(0)
          case Failure(error) =>
            println("gen_plaintexts error " + error)
            System.exit(-1)
        }
      }
      else
      {
        showHelp()
      }
    }
    ()
  }
}