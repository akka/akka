#!/bin/sh
exec scala "$0" "$@"
!#

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
import java.security.{MessageDigest, SecureRandom}

lazy val random = SecureRandom.getInstance("SHA1PRNG")

val buffer = Array.make(32, 0.byteValue)
random.nextBytes(buffer)                

val digest = MessageDigest.getInstance("MD5")
digest.update(buffer)
val bytes = digest.digest

val sb = new StringBuilder
val hex = "0123456789ABCDEF"
bytes.foreach { b =>
  val n = b.asInstanceOf[Int]
  sb.append(hex.charAt((n & 0xF) >> 4)).append(hex.charAt(n & 0xF))
}

println("Cryptographically secure cookie:")
println(sb.toString)
