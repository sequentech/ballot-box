/**
 * This file is part of Helios Voting, copyright (C) 2008 Ben Adida, 
 * distributed to the public under the GPL v3 license. 
 * 
 * You should have received a copy of the GNU  General Public License v3
 * along with Helios Voting.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Helios Voting can be downloaded at https://github.com/benadida/helios
 * 
**/

/*
 * Random Number generation, now uses the glue to Java
 */

Random = {};

Random.GENERATOR = null;

Random.setupGenerator = function() {
/*    if (Random.GENERATOR == null && !USE_SJCL) {
	    if (BigInt.use_applet) {
	      var foo = BigInt.APPLET.newSecureRandom();
	      Random.GENERATOR = BigInt.APPLET.newSecureRandom();
	    } else {
	      // we do it twice because of some weird bug;
	      var foo = new java.security.SecureRandom();
	      Random.GENERATOR = new java.security.SecureRandom();
	    }
    }
    */
};

Random.getRandomInteger = function(max) {
  var bit_length = max.bitLength();
  Random.setupGenerator();
  var random;
  random = sjcl.random.randomWords(bit_length / 32, 0);
  // we get a bit array instead of a BigInteger in this case
  var rand_bi = new BigInt(sjcl.codec.hex.fromBits(random), 16);
  return rand_bi.mod(max);
  return BigInt._from_java_object(random).mod(max);
};

