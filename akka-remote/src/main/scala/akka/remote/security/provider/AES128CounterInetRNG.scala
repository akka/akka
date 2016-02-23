/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.security.provider

import org.uncommons.maths.random.{ AESCounterRNG }
import SeedSize.Seed128

/**
 * INTERNAL API
 * This class is a wrapper around the 128-bit AESCounterRNG algorithm provided by http://maths.uncommons.org/
 * It uses the default seed generator which uses one of the following 3 random seed sources:
 * Depending on availability: random.org, /dev/random, and SecureRandom (provided by Java)
 * The only method used by netty ssl is engineNextBytes(bytes)
 */
@deprecated("Use AES128CounterSecureRNG instead", "2.4")
class AES128CounterInetRNG extends java.security.SecureRandomSpi {
  private val rng = new AESCounterRNG(engineGenerateSeed(Seed128))

  /**
   * This is managed internally by AESCounterRNG
   */
  override protected def engineSetSeed(seed: Array[Byte]): Unit = ()

  /**
   * Generates a user-specified number of random bytes.
   *
   * @param bytes the array to be filled in with random bytes.
   */
  override protected def engineNextBytes(bytes: Array[Byte]): Unit = rng.nextBytes(bytes)

  /**
   * Unused method
   * Returns the given number of seed bytes.  This call may be used to
   * seed other random number generators.
   *
   * @param numBytes the number of seed bytes to generate.
   * @return the seed bytes.
   */
  override protected def engineGenerateSeed(numBytes: Int): Array[Byte] = InternetSeedGenerator.getInstance.generateSeed(numBytes)
}

