/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import java.security.MessageDigest

class SystemFailure(cause: Throwable) extends RuntimeException(cause)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Helpers extends Logging {

  implicit def null2Option[T](t: T): Option[T] = if (t != null) Some(t) else None

  def intToBytes(value: Int): Array[Byte] = {
    val bytes = new Array[Byte](4)
    bytes(0) = (value >>> 24).asInstanceOf[Byte]
    bytes(1) = (value >>> 16).asInstanceOf[Byte]
    bytes(2) = (value >>> 8).asInstanceOf[Byte]
    bytes(3) = value.asInstanceOf[Byte]
    bytes
  }

  def getMD5For(s: String) = {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(s.getBytes("ASCII"))
    val bytes = digest.digest

    val sb = new StringBuilder
    val hex = "0123456789ABCDEF"
    bytes.foreach(b => {
      val n = b.asInstanceOf[Int]
      sb.append(hex.charAt((n & 0xF) >> 4)).append(hex.charAt(n & 0xF))
    })
    sb.toString
  }
}

