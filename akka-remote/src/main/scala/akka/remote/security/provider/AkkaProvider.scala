/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.security.provider

import java.security.{ PrivilegedAction, AccessController, Provider, Security }

/**
 * A provider that for AES128CounterRNGFast, a cryptographically secure random number generator through SecureRandom
 */
object AkkaProvider extends Provider("Akka", 1.0, "Akka provider 1.0 that implements a secure AES random number generator") {
  AccessController.doPrivileged(new PrivilegedAction[this.type] {
    def run = {
      //SecureRandom
      put("SecureRandom.AES128CounterSecureRNG", classOf[AES128CounterSecureRNG].getName)
      put("SecureRandom.AES256CounterSecureRNG", classOf[AES256CounterSecureRNG].getName)
      put("SecureRandom.AES128CounterInetRNG", classOf[AES128CounterInetRNG].getName)
      put("SecureRandom.AES256CounterInetRNG", classOf[AES256CounterInetRNG].getName)

      //Implementation type: software or hardware
      put("SecureRandom.AES128CounterSecureRNG ImplementedIn", "Software")
      put("SecureRandom.AES256CounterSecureRNG ImplementedIn", "Software")
      put("SecureRandom.AES128CounterInetRNG ImplementedIn", "Software")
      put("SecureRandom.AES256CounterInetRNG ImplementedIn", "Software")
      null //Magic null is magic
    }
  })
}

