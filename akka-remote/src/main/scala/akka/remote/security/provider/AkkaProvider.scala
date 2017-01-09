/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.security.provider

import java.security.{ PrivilegedAction, AccessController, Provider }

/**
 * A provider that for AES128CounterRNGFast, a cryptographically secure random number generator through SecureRandom
 */
object AkkaProvider extends Provider("Akka", 1.0, "Akka provider 1.0 that implements a secure AES random number generator") {
  AccessController.doPrivileged(new PrivilegedAction[this.type] {
    def run = {
      //SecureRandom
      put("SecureRandom.AES128CounterSecureRNG", classOf[AES128CounterSecureRNG].getName)
      put("SecureRandom.AES256CounterSecureRNG", classOf[AES256CounterSecureRNG].getName)

      //Implementation type: software or hardware
      put("SecureRandom.AES128CounterSecureRNG ImplementedIn", "Software")
      put("SecureRandom.AES256CounterSecureRNG ImplementedIn", "Software")
      null //Magic null is magic
    }
  })
}

