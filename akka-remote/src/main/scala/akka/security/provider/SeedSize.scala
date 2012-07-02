/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.security.provider

/**
 * Internal API
 * From AESCounterRNG API docs:
 * Valid values are 16 (128 bits), 24 (192 bits) and 32 (256 bits).
 * Any other values will result in an exception from the AES implementation.
 */
sealed trait SeedSize { def size: Int }
case object Seed128 extends SeedSize { val size = 16 }
case object Seed192 extends SeedSize { val size = 24 }
case object Seed256 extends SeedSize { val size = 32 }

