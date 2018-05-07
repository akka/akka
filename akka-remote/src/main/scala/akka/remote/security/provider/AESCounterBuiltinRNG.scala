/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.security.provider

import java.security.{ Key, SecureRandom }
import java.util.Random
import java.util.concurrent.ThreadFactory
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

import akka.annotation.InternalApi

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ Await, ExecutionContext, Future, duration }

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
 * NOTE: this class is not serializable
 */
@InternalApi
private[akka] class AESCounterBuiltinRNG(val seed: Array[Byte], implicit val executionContext: ExecutionContext,
                                         val reseedingThreshold: Long     = CounterRNGConstants.ReseedingThreshold,
                                         val reseedingDeadline:  Long     = CounterRNGConstants.ReseedingDeadline,
                                         val reseedingTimeout:   Duration = CounterRNGConstants.ReseedingTimeout) extends Random {
  import CounterRNGConstants._

  private val entropySource = new SecureRandom

  // mutable state below, concurrent accesses need synchronized or lock
  private val counter: Array[Byte] = Array.fill[Byte](CounterSizeBytes)(0)
  private var index: Int = 0
  private var currentBlock: Array[Byte] = null
  private var reseedFuture: Future[Array[Byte]] = null
  private var bitsSinceSeeding: Long = 0

  private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

  // this algorithm can be further improved by better selection of the iv
  // here and at re-seeding time further below
  private val ivArr = Array.fill[Byte](CounterSizeBytes)(0)
  ivArr(0) = (ivArr(0) + 1.toByte).toByte
  private val ivSpec = new IvParameterSpec(ivArr)
  cipher.init(Cipher.ENCRYPT_MODE, new this.AESKey(seed), ivSpec)

  @Override
  override protected def next(bits: Int): Int = synchronized {
    // random result generation phase - if there is not enough bits in counter variable
    // we generate some more with AES/CTR
    bitsSinceSeeding += bits
    if (currentBlock == null || currentBlock.length - index < 4) {
      try {
        currentBlock = cipher.doFinal(counter)
        index = 0
      } catch {
        case ex: Exception ⇒
          // Generally Cipher.doFinal() from nextBlock may throw various exceptions.
          // However this should never happen.  If initialisation succeeds without exceptions
          // we should be able to proceed indefinitely without exceptions.
          throw new IllegalStateException("Failed creating next random block.", ex)
      }
    }

    // now, enough bits in counter, generate pseudo-random result
    val result = (BitwiseByteToInt & currentBlock(index + 3)) |
      ((BitwiseByteToInt & currentBlock(index + 2)) << 8) |
      ((BitwiseByteToInt & currentBlock(index + 1)) << 16) |
      ((BitwiseByteToInt & currentBlock(index)) << 24)

    // re-seeding phase
    // first, we check if reseedingThreshold is exceeded to see if new entropy is required
    // we can still proceed without it, but we should ask for it
    if (bitsSinceSeeding > reseedingThreshold) {
      if (reseedFuture == null) {
        // ask for a seed and process async on a separate thread using AESCounterBuiltinRNGReSeeder threadpool
        reseedFuture = Future { entropySource.generateSeed(32) }
      }
      // check if reseedingDeadline is exceeded - in that case we cannot proceed, as that would be insecure
      // we need to block on the future to wait for entropy
      if (bitsSinceSeeding > reseedingDeadline) {
        try {
          Await.ready(reseedFuture, reseedingTimeout)
        } catch {
          case ex: Exception ⇒
            Console.err.println(s"[ERROR] AESCounterBuiltinRNG re-seeding failed or timed out after ${reseedingTimeout.toSeconds.toString}s !")
        }
      }
      // check if future has completed and retrieve additional entropy if that is the case
      if (reseedFuture != null && reseedFuture.isCompleted) {
        if (reseedFuture.value.get.isSuccess) { // we have re-seeded with success
          val newSeed = reseedFuture.value.get.get // this is safe
          cipher.init(Cipher.ENCRYPT_MODE, new this.AESKey(newSeed), ivSpec)
          currentBlock = null
          bitsSinceSeeding = 0 // reset re-seeding counter
        }
        reseedFuture = null // request creation of new seed when needed
      }
    }

    index += 4
    result >>> (32 - bits)
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

private object CounterRNGConstants {
  final val CounterSizeBytes = 16
  final val BitwiseByteToInt = 0x000000FF
  final val ReseedingThreshold = 1000000000L // threshold for requesting new entropy (should give us ample time to re-seed)
  final val ReseedingDeadline = 140737488355328L // deadline for obtaining new entropy 2^47 safe as per SP800-90
  final val ReseedingTimeout: FiniteDuration = Duration.apply(5, duration.MINUTES) // timeout for re-seeding (on Linux read from /dev/random)
}

private class AESCounterBuiltinRNGReSeeder extends ThreadFactory {
  override def newThread(r: Runnable): Thread = {
    val thread = new Thread(r, "AESCounterBuiltinRNGReSeeder")
    thread.setDaemon(true)
    thread
  }
}
