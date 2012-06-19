/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.security.provider

import java.security.{ PrivilegedAction, AccessController, Provider, Security }

/**
 * A provider that for AES128CounterRNGFast, a cryptographically secure random number generator through SecureRandom
 */
object AkkaProvider extends Provider("Akka", 1.0, "Akka provider 1.0 that implements a secure AES random number generator") {
  AccessController.doPrivileged(new PrivilegedAction[this.type] {
    def run = {
      //SecureRandom
      put("SecureRandom.AES128CounterRNGFast", classOf[AES128CounterRNGFast].getName)
      put("SecureRandom.AES128CounterRNGSecure", classOf[AES128CounterRNGSecure].getName)
      put("SecureRandom.AES256CounterRNGSecure", classOf[AES256CounterRNGSecure].getName)

      //Implementation type: software or hardware
      put("SecureRandom.AES128CounterRNGFast ImplementedIn", "Software")
      put("SecureRandom.AES128CounterRNGSecure ImplementedIn", "Software")
      put("SecureRandom.AES256CounterRNGSecure ImplementedIn", "Software")
      null //Magic null is magic
    }
  })
}

