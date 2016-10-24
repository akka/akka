/**
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.security.provider

import java.security.Key
import java.util.Random
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher

/**
 * INTERNAL API
 * This class is a Scala implementation of AESCounterRNG algorithm
 * patterned after org.uncommons.maths.random by Daniel Dyer (Apache License 2.0)
 *
 * Non-linear random number generator based on the AES block cipher in counter mode.
 * Uses the seed as a key to encrypt a 128-bit counter using AES(Rijndael).
 *
 * Keys larger than 128-bit for the AES cipher require
 * the inconvenience of installing the unlimited strength cryptography policy
 * files for the Java platform.  Larger keys may be used (192 or 256 bits) but if the
 * cryptography policy files are not installed, a
 * java.security.GeneralSecurityException will be thrown.
 *
 * NOTE: THIS CLASS IS NOT SERIALIZABLE
 */

class AESCounterBuiltinRNG(val seed: Array[Byte]) extends Random {
  private val COUNTER_SIZE_BYTES = 16
  private val BITWISE_BYTE_TO_INT = 0x000000FF

  // mutable state below, concurrent accesses need lock
  private val lock = new ReentrantLock
  private val counter: Array[Byte] = new Array[Byte](COUNTER_SIZE_BYTES)
  private var index: Int = 0
  private var currentBlock: Array[Byte] = null

  private val cipher = Cipher.getInstance("AES/ECB/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE, new this.AESKey(seed))

  /**
   * Generates a single block of random data
   * (by default 128-bit block - 16 bytes should be used).
   */
  private def nextBlock: Array[Byte] = {
    var i: Int = 0
    if (i < counter.length) {
      do {
        counter(i) = (counter(i) + 1.toByte).toByte
        i += 1
      } while ((i < counter.length) && (counter(i - 1) == 0))
    }
    cipher.doFinal(counter)
  }

  @Override
  override protected def next(bits: Int): Int = {
    try {
      lock.lock()
      if (currentBlock == null || currentBlock.length - index < 4) {
        try {
          currentBlock = nextBlock
          index = 0
        } catch {
          case ex: Exception ⇒ {
            // Generally Cipher.doFinal() from nextBlock may throw various exceptions.
            // However this should never happen.  If initialisation succeeds without exceptions
            // we should be able to proceed indefinitely without exceptions.
            throw new IllegalStateException("Failed creating next random block.", ex)
          }
        }
      }
      val result = (BITWISE_BYTE_TO_INT & currentBlock(index + 3)) |
        ((BITWISE_BYTE_TO_INT & currentBlock(index + 2)) << 8) |
        ((BITWISE_BYTE_TO_INT & currentBlock(index + 1)) << 16) |
        ((BITWISE_BYTE_TO_INT & currentBlock(index)) << 24)

      index += 4
      result >>> (32 - bits)
    } finally {
      lock.unlock()
    }
  }

  /**
   * Trivial key implementation for use with AES cipher.
   */
  final private class AESKey(val keyData: Array[Byte]) extends Key {
    def getAlgorithm: String = "AES"
    def getFormat: String = "RAW"
    def getEncoded: Array[Byte] = keyData
  }
}
