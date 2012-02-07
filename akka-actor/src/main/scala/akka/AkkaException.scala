/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.newUuid
import java.net.{ InetAddress, UnknownHostException }

object AkkaException {
  val hostname = try InetAddress.getLocalHost.getHostAddress catch { case e: UnknownHostException ⇒ "unknown host" }

  def toStringWithStackTrace(throwable: Throwable): String = throwable match {
    case null              ⇒ "Unknown Throwable: was 'null'"
    case ae: AkkaException ⇒ ae.toLongString
    case e                 ⇒ "%s:%s\n%s" format (e.getClass.getName, e.getMessage, stackTraceToString(e))
  }

  def stackTraceToString(throwable: Throwable): String = {
    val trace = throwable.getStackTrace
    val sb = new StringBuilder
    for (i ← 0 until trace.length)
      sb.append("\tat %s\n" format trace(i))
    sb.toString
  }

}

/**
 * Akka base Exception. Each Exception gets:
 * <ul>
 *   <li>a uuid for tracking purposes</li>
 *   <li>toString that includes exception name, message and uuid</li>
 *   <li>toLongString which also includes the stack trace</li>
 * </ul>
 */
class AkkaException(message: String = "", cause: Throwable = null) extends RuntimeException(message, cause) with Serializable {
  val uuid = "%s_%s".format(AkkaException.hostname, newUuid)

  override lazy val toString =
    "%s:%s\n[%s]".format(getClass.getName, message, uuid)

  lazy val toLongString =
    "%s:%s\n[%s]\n%s".format(getClass.getName, message, uuid, stackTraceToString)

  def this(msg: String) = this(msg, null);

  def stackTraceToString = AkkaException.stackTraceToString(this)
}
