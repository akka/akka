/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import java.io.{ PrintWriter, StringWriter }
import java.util.Comparator
import scala.annotation.tailrec
import java.util.regex.Pattern

object Helpers {

  def makePattern(s: String): Pattern = Pattern.compile("^\\Q" + s.replace("?", "\\E.\\Q").replace("*", "\\E.*\\Q") + "\\E$")

  def compareIdentityHash(a: AnyRef, b: AnyRef): Int = {
    /*
     * make sure that there is no overflow or underflow in comparisons, so
     * that the ordering is actually consistent and you cannot have a
     * sequence which cyclically is monotone without end.
     */
    val diff = ((System.identityHashCode(a) & 0xffffffffL) - (System.identityHashCode(b) & 0xffffffffL))
    if (diff > 0) 1 else if (diff < 0) -1 else 0
  }

  val IdentityHashComparator = new Comparator[AnyRef] {
    def compare(a: AnyRef, b: AnyRef): Int = compareIdentityHash(a, b)
  }

  final val base64chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+~"

  @tailrec
  def base64(l: Long, sb: StringBuilder = new StringBuilder("$")): String = {
    sb += base64chars.charAt(l.toInt & 63)
    val next = l >>> 6
    if (next == 0) sb.toString
    else base64(next, sb)
  }

  def ignore[E: Manifest](body: ⇒ Unit) {
    try {
      body
    } catch {
      case e if manifest[E].erasure.isAssignableFrom(e.getClass) ⇒ ()
    }
  }

  def withPrintStackTraceOnError(body: ⇒ Unit) {
    try {
      body
    } catch {
      case e: Throwable ⇒
        val sw = new java.io.StringWriter()
        var root = e
        while (root.getCause ne null) root = e.getCause
        root.printStackTrace(new java.io.PrintWriter(sw))
        System.err.println(sw.toString)
        throw e
    }
  }
}
