package utils

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter;

 object HMAC {
  def hmac(secret: String, value: String): String = {
    val key = new SecretKeySpec(secret.getBytes, "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    val result = mac.doFinal(value.getBytes)
    // http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
    return DatatypeConverter.printHexBinary(result).toLowerCase
  }

  // use runMain utils.HMAC <secret> <message> from console
  // or
  // activator "run-main utils.HMAC sdfasdfasdf asdfadfadsf"
  // from CLI
  def main(args: Array[String]) : Unit = {
    println(HMAC.hmac(args(0), args(1)))
  }
}