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
    val pk = pks(0)

    val jsonVotes = Json.parse(scala.io.Source.fromFile(args(1)).mkString)
    val votes = jsonVotes.validate[Array[Long]].get

    val toEncrypt = if(args.length == 3) {
      val extraSize = (args(2).toInt - votes.length).max(0)
      val extra = Array.fill(extraSize){ votes(scala.util.Random.nextInt(votes.length)) }
      votes ++ extra
    } else {
      votes
    }

    val histogram = toEncrypt.groupBy(l => l).map(t => (t._1, t._2.length))
    System.err.println("DemoVotes: tally: " + histogram)

    val jsonEncrypted = Json.toJson(toEncrypt.par.map(Crypto.encrypt(pk, _)).seq)
    println(jsonEncrypted)
  }
}