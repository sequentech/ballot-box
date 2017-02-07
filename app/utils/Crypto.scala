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

  val zero = BigInt(0)
  val two = BigInt(2)
  val three = BigInt(3)
  val four = BigInt(4)
  val five = BigInt(5)
  val eight = BigInt(8)

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

  /** encode and then encrypt a plaintext */
  def encrypt(pks: Array[PublicKey], values: Array[Long]) = {
    val encoded = pks.view.zipWithIndex.map( t =>
      encode(t._1, values(t._2))
    ).toArray
    encryptEncoded(pks, encoded)
  }

  /** return a random bigint between 0 and max - 1 */
  def randomBigInt(max: BigInt) = {
    val rnd = new java.util.Random()
    var r = new BigInteger(max.underlying.bitLength, rnd)

    while (r.compareTo(max.underlying) >= 0) {
      r = new BigInteger(max.underlying.bitLength, rnd)
    }
    BigInt(r)
  }

  /** encode plaintext into subgroup defined by pk */
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

  /** encrypt an encoded plaintext */
  def encryptEncoded(pks: Array[PublicKey], values: Array[BigInt]) = {
    var choices = Array[Choice]()
    var proofs = Array[Popk]()
    for ( index <- 0 until pks.length ) {
      val pk = pks(index)
      val value = values(index)
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
      choices :+= Choice(alpha, beta)
      proofs :+= Popk(challenge, commitment, response)
    }
    EncryptedVote(choices, "now", proofs)
  }
}