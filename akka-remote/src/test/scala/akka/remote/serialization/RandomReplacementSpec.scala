/**
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.serialization

import akka.remote.security.provider.AESCounterBuiltinRNG
import akka.testkit.AkkaSpec
import org.uncommons.maths.random.{ AESCounterRNG, SecureRandomSeedGenerator }

class RandomReplacementSpec extends AkkaSpec {
  "Secure random replacement" must {
    "generate the same" in {
      val rng = new FakeAES128CounterSecureRNG
      val rng2 = new FakeAES128NewCounterSecureRNG
      val bytes = rng.getBytes().toList.map(_.toInt).toString
      val bytes2 = rng2.getBytes.toList.map(_.toInt).toString
      bytes should ===(bytes2)
    }
  }
}

private class FakeAES128NewCounterSecureRNG {
  // stubbed for testing
  private val seed: Array[Byte] = Array(
    1.toByte, 1.toByte, 1.toByte, 1.toByte,
    1.toByte, 1.toByte, 1.toByte, 1.toByte,
    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
    1.toByte, 1.toByte, 1.toByte, 1.toByte
  )

  private val rng = new AESCounterBuiltinRNG(seed)

  // helper method, for test purposes only
  def getBytes = {
    var bytes = Array[Byte](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
    rng.nextBytes(bytes)
    bytes
  }
}

private class FakeAES128CounterSecureRNG {
  /**Singleton instance. */
  private final val Instance: SecureRandomSeedGenerator = new SecureRandomSeedGenerator

  // stub for test purposes
  private val seed: Array[Byte] = Array(
    1.toByte, 1.toByte, 1.toByte, 1.toByte,
    1.toByte, 1.toByte, 1.toByte, 1.toByte,
    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
//    1.toByte, 1.toByte, 1.toByte, 1.toByte,
    1.toByte, 1.toByte, 1.toByte, 1.toByte
  )

  private val rng = new AESCounterRNG(seed)

  // helper method for test purposes only
  def getBytes() = {
    var bytes = Array[Byte](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
    rng.nextBytes(bytes)
    bytes
  }
}

