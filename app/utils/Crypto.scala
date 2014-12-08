package utils

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter

 /**
  * Crypto utilities
  *
  */
 object Crypto {

  /** calculates an hmac */
  def hmac(secret: String, value: String): String = {
    val key = new SecretKeySpec(secret.getBytes, "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    val result = mac.doFinal(value.getBytes)
    // http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
    DatatypeConverter.printHexBinary(result).toLowerCase
  }

  /** calculate sha256 */
  def sha256(text: String) = {
    import java.security.MessageDigest

    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(text.getBytes("UTF-8"))
    DatatypeConverter.printHexBinary(bytes).toLowerCase
  }

  /** calculate whether the given value is a quadratic residue in the given modulus */
  def quadraticResidue(value: BigInt, modulus: BigInt): Boolean = {
    legendre(value, modulus) == 1
  }

  /** calculate the jacobi symbol (and hence legendre, since modulus is odd prime)
    *
    * algorithm taken from verificatum https://github.com/agoravoting/verificatum/blob/master/verificatum%2Fverificatum%2Farithm%2FLargeInteger.magic#L1621
    *
    * and http://programmingpraxis.com/2012/05/01/legendres-symbol/
    *
    */
  def legendre(value: BigInt, mod: BigInt): Long = {
    val zero = BigInt(0)
    val two = BigInt(2)
    val three = BigInt(3)
    val four = BigInt(4)
    val five = BigInt(5)
    val eight = BigInt(8)

    var modulus = mod
    var a = value.mod(modulus)
    var t = BigInt(1)

    while (! a.equals(zero)) {
      while (a.mod(two).equals(zero)) {
        a = a / two

        if (modulus.mod(eight).equals(three) || modulus.mod(eight).equals(five)) {
          t = -t
        }
      }

      // swap
      /* tmp := big.NewInt(0)
      tmp.SetBytes(a.Bytes())
      a.SetBytes(modulus.Bytes())
      modulus.SetBytes(tmp.Bytes())*/
      val tmp = a
      a = modulus
      modulus = tmp

      if (a.mod(four).equals(three) && modulus.mod(four).equals(three)) {
        t = -t
      }
      a = a.mod(modulus)
    }

    if (modulus.equals(BigInt(1))) {
      t.toLong
    }
    else {
      0
    }
  }

  /** main hook to generate hmacs
    *
    * use runMain utils.Crypto <secret> <message> from console
    * or
    * activator "run-main utils.Crypto sdfasdfasdf asdfadfadsf"
    * from CLI
    */
  def main(args: Array[String]) : Unit = {
    println(Crypto.hmac(args(0), args(1)))
  }
}