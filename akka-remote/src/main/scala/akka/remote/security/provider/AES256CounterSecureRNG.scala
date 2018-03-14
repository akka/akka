/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.security.provider

import java.security.SecureRandom
import java.util.concurrent.Executors

import SeedSize.Seed256

import scala.concurrent.ExecutionContext

/**
 * This class is a wrapper around the 256-bit AESCounterBuiltinRNG AES/CTR PRNG algorithm
 * The only method used by netty ssl is engineNextBytes(bytes)
 */
class AES256CounterSecureRNG extends java.security.SecureRandomSpi {
  private val singleThreadPool = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(new AESCounterBuiltinRNGReSeeder))
  private val entropySource = new SecureRandom
  private val seed = entropySource.generateSeed(Seed256)

  private val rng = new AESCounterBuiltinRNG(seed, singleThreadPool)

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
   * For completeness of SecureRandomSpi API implementation
   * Returns the given number of seed bytes.
   *
   * @param numBytes the number of seed bytes to generate.
   * @return the seed bytes.
   */
  override protected def engineGenerateSeed(numBytes: Int): Array[Byte] = entropySource.generateSeed(numBytes)
}
