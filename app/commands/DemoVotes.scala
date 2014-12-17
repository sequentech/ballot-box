package commands

import models._
import utils.JsonFormatters._
import utils.Crypto

import play.api.libs.json._

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import java.math.BigInteger


/** main hook to generate votes
  *
  * use runMain commands.DemoVotes <pks> <votes> from console
  * or
  * activator "run-main commands.DemoVotes <pks> <votes>"
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
      val extraSize = (args(2).toInt - votes.length).min(0)
      val extra = Array.fill(extraSize){ votes(scala.util.Random.nextInt(votes.length)) }
      votes ++ extra
    } else {
      votes
    }

    val jsonEncrypted = Json.toJson(toEncrypt.map(Crypto.encrypt(pk, _)))
    println(jsonEncrypted)
  }
}