package utils

import models._

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import java.math.BigInteger

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
    *
    * online calculator here http://maxima-online.org/?inc=r1919443628
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

  def encrypt(pk: PublicKey, value: Long) = {
    val encoded = encode(pk, value)
    encryptEncoded(pk, encoded)
  }

  def randomBigInt(max: BigInt) = {
    val rnd = new java.util.Random()
    var r = new BigInteger(max.underlying.bitLength, rnd)

    while (r.compareTo(max.underlying) >= 0) {
      r = new BigInteger(max.underlying.bitLength, rnd)
    }
    BigInt(r)
  }

  def encode(pk: PublicKey, value: Long) = {
    val one = BigInt(1)
    val m = BigInt(value + 1)
    val test = m.modPow(pk.q, pk.p)
    if (test.equals(one)) {
      m
    } else {
      -m.mod(pk.p)
    }
  }

  def encryptEncoded(pk: PublicKey, value: BigInt) = {
    val r = randomBigInt(pk.q)
    val alpha = pk.g.modPow(r, pk.p)
    val beta = (value * (pk.y.modPow(r, pk.p))).mod(pk.p)

    // prove knowledge
    val w = randomBigInt(pk.q)

    val commitment = pk.g.modPow(w, pk.p)

    val toHash = s"${alpha.toString}/${commitment.toString}"
    val challenge = BigInt(sha256(toHash), 16)

    // compute response = w +  randomness * challenge
    val response = (w + (r * challenge)).mod(pk.q)

    // RawVote(alpha, beta, commitment, challenge, response)
    // case class EncryptedVote(a: String, choices: Array[Choice], election_hash: ElectionHash, issue_date: String, proofs: Array[Popk]) {

    EncryptedVote(Array(Choice(alpha, beta)), "now", Array(Popk(challenge, commitment, response)))
  }
}