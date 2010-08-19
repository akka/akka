/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import java.security.MessageDigest

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

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will throw a ClassCastException
   * if the actual type is not assignable from the given one.
   */
  def narrow[T](o: Option[Any]): Option[T] = {
    require(o != null, "Option to be narrowed must not be null!")
    o.asInstanceOf[Option[T]]
  }

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will swallow a possible
   * ClassCastException and return None in that case.
   */
  def narrowSilently[T: Manifest](o: Option[Any]): Option[T] =
    try {
      narrow(o)
    } catch {
      case e: ClassCastException =>
        log.warning(e, "Cannot narrow %s to expected type %s!", o, implicitly[Manifest[T]].erasure.getName)
        None
    }
}
