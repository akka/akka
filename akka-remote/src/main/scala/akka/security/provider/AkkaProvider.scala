/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.security.provider

import java.security.{ PrivilegedAction, AccessController, Provider }

/**
 * A provider that for AES128CounterRNGFast, a cryptographically secure random number generator through SecureRandom
 */
final class AkkaProvider extends Provider("Akka", 1.0, "Akka provider 1.0 that implements a secure AES random number generator") {
  AccessController.doPrivileged(new PrivilegedAction[AkkaProvider] {
    def run = {
      /**
       * SecureRandom
       */
      put("SecureRandom.AES128CounterRNGFast", "akka.security.provider.AES128CounterRNGFast")
      put("SecureRandom.AES128CounterRNGSecure", "akka.security.provider.AES128CounterRNGSecure")
      put("SecureRandom.AES256CounterRNGSecure", "akka.security.provider.AES256CounterRNGSecure")

      /**
       * Implementation type: software or hardware
       */
      put("SecureRandom.AES128CounterRNGFast ImplementedIn", "Software")
      put("SecureRandom.AES128CounterRNGSecure ImplementedIn", "Software")
      put("SecureRandom.AES256CounterRNGSecure ImplementedIn", "Software")
      null
    }
  })
}

