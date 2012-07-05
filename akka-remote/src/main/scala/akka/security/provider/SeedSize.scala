/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.security.provider

/**
 * Internal API
 * From AESCounterRNG API docs:
 * Valid values are 16 (128 bits), 24 (192 bits) and 32 (256 bits).
 * Any other values will result in an exception from the AES implementation.
 *
 * Internal API
 */
private[provider] object SeedSize {
  val Seed128 = 16
  val Seed192 = 24
  val Seed256 = 32
}

