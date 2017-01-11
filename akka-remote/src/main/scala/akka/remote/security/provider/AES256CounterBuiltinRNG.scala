/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.security.provider

import java.security.SecureRandom

import akka.remote.security.provider.SeedSize.Seed256

/**
 * INTERNAL API
 * This class is a wrapper around the 256-bit AESCounterBuiltinRNG algorithm
 * The only method used by netty ssl is engineNextBytes(bytes)
 */
class AES256CounterBuiltinRNG extends java.security.SecureRandomSpi {
  /**Singleton instance. */
  private final val SOURCE: SecureRandom = new SecureRandom

  private val rng = new AESCounterBuiltinRNG(engineGenerateSeed(Seed256))

  /**
   * This is managed internally by AESCounterBuiltinRNG
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
  override protected def engineGenerateSeed(numBytes: Int): Array[Byte] = SOURCE.generateSeed(numBytes)
}

