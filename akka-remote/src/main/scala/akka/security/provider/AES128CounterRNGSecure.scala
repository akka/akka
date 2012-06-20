/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.security.provider

import org.uncommons.maths.random.{ AESCounterRNG, DefaultSeedGenerator }

/**
 * Internal API
 */
class AES128CounterRNGSecure extends java.security.SecureRandomSpi {
  private val rng = new AESCounterRNG()

  /**
   * This is managed internally only
   */
  override protected def engineSetSeed(seed: Array[Byte]): Unit = ()

  /**
   * Generates a user-specified number of random bytes.
   *
   * @param bytes the array to be filled in with random bytes.
   */
  override protected def engineNextBytes(bytes: Array[Byte]): Unit = rng.nextBytes(bytes)

  /**
   * Returns the given number of seed bytes.  This call may be used to
   * seed other random number generators.
   *
   * @param numBytes the number of seed bytes to generate.
   * @return the seed bytes.
   */
  override protected def engineGenerateSeed(numBytes: Int): Array[Byte] = DefaultSeedGenerator.getInstance.generateSeed(numBytes)
}

