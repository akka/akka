#!/bin/sh
exec scala "$0" "$@"
!#

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
import java.security.{MessageDigest, SecureRandom}

lazy val random = SecureRandom.getInstance("SHA1PRNG")

val buffer = Array.fill(32)(0.byteValue)
random.nextBytes(buffer)                

val digest = MessageDigest.getInstance("SHA1")
digest.update(buffer)
val bytes = digest.digest

val sb = new StringBuilder
val hex = "0123456789ABCDEF"
bytes.foreach { b =>
  val n = b.asInstanceOf[Int]
  sb.append(hex.charAt((n & 0xF) >> 4)).append(hex.charAt(n & 0xF))
}

print("""
# This config imports the Akka reference configuration.
include "akka-reference.conf"

# In this file you can override any option defined in the 'akka-reference.conf' file.
# Copy in all or parts of the 'akka-reference.conf' file and modify as you please.

akka {
  remote {
    secure-cookie = """")
print(sb.toString)
print(""""
  }
}
""")

