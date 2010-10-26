/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import java.security.{MessageDigest, SecureRandom}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Crypt {
  lazy val random = SecureRandom.getInstance("SHA1PRNG")

  def generateSecureCookie: String = {
    val bytes = Array.make(32, 0.byteValue)
    random.nextBytes(bytes)
    getMD5For(bytes)
  }

  def getMD5For(s: String): String = getMD5For(s.getBytes("ASCII"))

  def getMD5For(b: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(b)
    val bytes = digest.digest

    val sb = new StringBuilder
    val hex = "0123456789ABCDEF"
    bytes.foreach { b =>
      val n = b.asInstanceOf[Int]
      sb.append(hex.charAt((n & 0xF) >> 4)).append(hex.charAt(n & 0xF))
    }
    sb.toString
  }
}