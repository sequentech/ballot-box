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
package commands

import models._
import utils.JsonFormatters._
import utils.Crypto

import play.api.libs.json._

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import java.math.BigInteger


/**
  * Encrypts votes given a pk, plaintext file and desired total
  *
  * use runMain commands.DemoVotes <pks> <votes> <total> from console
  * or
  * activator "run-main commands.DemoVotes <pks> <votes> <total>"
  * from CLI
  */
object DemoVotes {

  def main(args: Array[String]) : Unit = {
    val jsonPks = Json.parse(scala.io.Source.fromFile(args(0)).mkString)
    val pks = jsonPks.validate[Array[PublicKey]].get

    val jsonVotes = Json.parse(scala.io.Source.fromFile(args(1)).mkString)
    val votes = jsonVotes.validate[Array[Array[Long]]].get

    val toEncrypt = if(args.length == 3) {
      val extraSize = (args(2).toInt - votes.length).max(0)
      val extra = Array.fill(extraSize){ votes(scala.util.Random.nextInt(votes.length)) }
      votes ++ extra
    } else {
      votes
    }

    val histogram = toEncrypt.groupBy(l => l).map(t => (t._1, t._2.length))
    System.err.println("DemoVotes: tally: " + histogram)

    val jsonEncrypted = Json.toJson(toEncrypt.par.map(Crypto.encrypt(pks, _)).seq)
    println(jsonEncrypted)
  }
}