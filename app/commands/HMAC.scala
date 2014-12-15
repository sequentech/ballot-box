package commands

import utils._
import models._

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import java.math.BigInteger


/** main hook to generate hmacs
  *
  * use runMain commands.HMAC <secret> <message> from console
  * or
  * activator "run-main commands.HMAC <secret> <message>"
  * from CLI
  */
object HMAC {

  def main(args: Array[String]) : Unit = {
    println(Crypto.hmac(args(0), args(1)))
  }
}