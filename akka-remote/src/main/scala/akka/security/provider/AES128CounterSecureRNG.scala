/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.security.provider

import org.uncommons.maths.random.{ AESCounterRNG, SecureRandomSeedGenerator }

/**
 * Internal API
 * This class is a wrapper around the AESCounterRNG algorithm provided by http://maths.uncommons.org/ *
 * The only method used by netty ssl is engineNextBytes(bytes)
 * This RNG is good to use to prevent startup delay when you don't have Internet access to random.org
 */
class AES128CounterSecureRNG extends java.security.SecureRandomSpi {
  private val rng = new AESCounterRNG(new SecureRandomSeedGenerator())

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
  override protected def engineGenerateSeed(numBytes: Int): Array[Byte] = (new SecureRandomSeedGenerator()).generateSeed(numBytes)
}

