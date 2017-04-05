/**
 * This file is part of agora_elections.
 * Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

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
package utils

import play.api.libs.json._
import play.api._
import play.api.Play.current

import java.io.File
import java.io.RandomAccessFile
import java.nio._
import javax.xml.bind.DatatypeConverter
import java.nio.file.StandardOpenOption._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

/**
  * file system election datastore
  *
  */
object Datastore {

  // we deliberately crash startup if these are not set
  val urlRoot = Play.current.configuration.getString("app.datastore.root").get
  val urlSSLRoot = Play.current.configuration.getString("app.datastore.ssl_root").get
  val publicDs = Play.current.configuration.getString("app.datastore.public").get
  val privateDs = Play.current.configuration.getString("app.datastore.private").get

  val CIPHERTEXTS = "ciphertexts"
  val REMOVED_VOTE_HASHES = "removed_vote_hashes"
  val PKS = "pks"
  val TALLY = "tally.tar.gz"
  val CONFIG = "config.json"
  val RESULTS = "results.json"
  val RESULTS_DIR_PREFIX = "results-"

  /** writes a file to an election's datastore */
  def writeFile(electionId: Long, fileName: String, content: String, public: Boolean = false, append: Boolean = false) = {
    val path = getPath(electionId, fileName, public)
    val mode = if(append) {
       Files.write(path, content.getBytes(StandardCharsets.UTF_8), APPEND)
    } else {
      Files.write(path, content.getBytes(StandardCharsets.UTF_8), CREATE, TRUNCATE_EXISTING)
    }

    path
  }

  /** dumps the pks from db into the datastore */
  def dumpPks(electionId: Long, pks: String) = {
    writeFile(electionId, PKS, pks, true)
  }

  /** writes the agora-results config file (passed in api call) to disk, to call agora-results */
  def writeResultsConfig(electionId: Long, config: String) = {
    writeFile(electionId, CONFIG, config, false)
  }

  /** opens stream to write the votes file */
  def getVotesStream(electionId: Long) = {
    val path = getPath(electionId, CIPHERTEXTS)
    Files.newOutputStream(path)
  }

  /** opens stream to write the removed vote hashes file */
  def getRemovedVoteHashesStream(electionId: Long) = {
    val path = getPath(electionId, REMOVED_VOTE_HASHES)
    Files.newOutputStream(path)
  }

  /** opens stream to write the tally file */
  def getTallyStream(electionId: Long) = {
    val path = getPath(electionId, TALLY)
    Files.newOutputStream(path)
  }

  /** gets the ciphertext url that eo will use. requires proper configuration of nginx to match */
  def getCiphertextsUrl(electionId: Long) = {
     s"$urlSSLRoot" + s"/private/$electionId/ciphertexts"
  }

  /** incrementally calculates sha256 hash of votes using java nio apis */
  def hashVotes(electionId: Long) = {
    import java.security.MessageDigest

    val path = Paths.get(getStore(false), electionId.toString, CIPHERTEXTS)
    val inChannel = new RandomAccessFile(path.toString, "r").getChannel()
    val buffer = ByteBuffer.allocateDirect(10 * 1024)
    val digest = MessageDigest.getInstance("SHA-256")

    while(inChannel.read(buffer) > 0) {
      buffer.flip()
      digest.update(buffer)
      buffer.clear()
    }
    inChannel.close()

    val bytes = digest.digest()
    DatatypeConverter.printBase64Binary(bytes).replace("+", "-").replace("/", "_")
  }

  /** returns the complete path for some file in the datastore */
  def getPath(electionId: Long, fileName: String, public: Boolean = false) = {
    val directory = Paths.get(getStore(public), electionId.toString)
    ensureDirectory(directory)
    directory.resolve(fileName)
  }

  /** returns the election private directory path */
  def getDirPath(electionId: Long, public: Boolean = false) = {
    Paths.get(getStore(public), electionId.toString)
  }

  /** returns the path to the tally */
  def getTallyPath(electionId: Long) = {
    getPath(electionId, TALLY, false)
  }

  /** makes results public: creates a symbolic link to the tally, and creates a file with the results */
  def publishResults(
    electionId: Long,
    results: Option[String],
    subtallies: Array[Long]
  ) = {
    val tarLink = getPath(electionId, s"$electionId.tar", true)
    val tarTarget = getPath(electionId, s"$electionId.tar", false)
    val tallyLink = getPath(electionId, TALLY, true)
    val tallyTarget = getPath(electionId, TALLY, false)

    if(Files.exists(tallyTarget))
    {
      Files.deleteIfExists(tallyLink)
      Files.createSymbolicLink(tallyLink, tallyTarget)
    }
    else if (subtallies.size == 0)
    {
      Logger.warn(s"publishResults: tally does not exist for $electionId")
      throw new java.io.FileNotFoundException("tally does not exist")
    }

    if(Files.exists(tarTarget)) {
      Files.deleteIfExists(tarLink)
      Files.createSymbolicLink(tarLink, tarTarget)
    }
    else {
      Logger.warn(s"publishResults: tar does not exist for $electionId - $tarTarget")
      throw new java.io.FileNotFoundException("tar does not exist")
    }

    results match {
      case Some(result) => writeFile(electionId, RESULTS, result, true)
      case None => {
        Logger.warn(s"publishResults: results do not exist for $electionId")
        throw new IllegalStateException("results not available")
      }
    }
  }

  /** ensures that a given directory exists */
  private def ensureDirectory(path: java.nio.file.Path) = {
    if(!Files.isDirectory(path)) {
      Files.createDirectory(path)
    }
  }

  /** returns the path to the private or public datastore area */
  private def getStore(public: Boolean) = {
    if(public) {
      publicDs
    } else {
      privateDs
    }
  }


  // UNUSED remove

  /** reads a file from an election's datastore */
  def readFile(electionId: Long, file: String, public: Boolean = true) = {
    val path = getStore(public) + File.separator + electionId.toString + File.separator + file
    scala.io.Source.fromFile(path).mkString
  }

  def readFileJson(electionId: Long, file: String, public: Boolean = true) = {
    val contents = readFile(electionId, file, public)

    Json.parse(contents)
  }

  def readPublicKey(electionId: Long) = {
    readFileJson(electionId, PKS)
  }
}
