/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.security.provider

import java.security.{ PrivilegedAction, AccessController, Provider }

/**
 * A provider that for AES128CounterRNGFast, a cryptographically secure random number generator through SecureRandom
 *
 */
@deprecated("Use SecureRandom instead. We cannot prove that this code is correct, see https://doc.akka.io/docs/akka/current/security/2018-08-29-aes-rng.html", "2.5.16")
object DeprecatedAkkaProvider extends Provider("Akka", 1.0, "Akka provider 1.0 that implements a secure AES random number generator") {
  AccessController.doPrivileged(new PrivilegedAction[this.type] {
    def run = {
      //SecureRandom
      put("SecureRandom.DeprecatedAES128CounterSecureRNG", classOf[DeprecatedAES128CounterSecureRNG].getName)
      put("SecureRandom.DeprecatedAES256CounterSecureRNG", classOf[DeprecatedAES256CounterSecureRNG].getName)

      //Implementation type: software or hardware
      put("SecureRandom.DeprecatedAES128CounterSecureRNG ImplementedIn", "Software")
      put("SecureRandom.DeprecatedAES256CounterSecureRNG ImplementedIn", "Software")
      null //Magic null is magic
    }
  })
}

