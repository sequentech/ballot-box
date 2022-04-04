/**
 * This file is part of ballot_box.
 * Copyright (C) 2014-2016  Sequent Tech Inc <legal@sequentech.io>

 * ballot_box is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * ballot_box  is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.
**/
package commands

import utils._
import models._

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.xml.bind.DatatypeConverter
import java.math.BigInteger


/**
  * Generates an HMAC
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