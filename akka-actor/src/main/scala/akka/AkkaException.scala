/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.newUuid
import java.net.{ InetAddress, UnknownHostException }

/**
 * Akka base Exception. Each Exception gets:
 * <ul>
 *   <li>a uuid for tracking purposes</li>
 *   <li>toString that includes exception name, message and uuid</li>
 *   <li>toLongString which also includes the stack trace</li>
 * </ul>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AkkaException(message: String = "", cause: Throwable = null) extends RuntimeException(message, cause) with Serializable {
  val uuid = "%s_%s".format(AkkaException.hostname, newUuid)

  override lazy val toString =
    "%s: %s\n[%s]".format(getClass.getName, message, uuid)

  lazy val toLongString =
    "%s: %s\n[%s]\n%s".format(getClass.getName, message, uuid, stackTraceToString)

  def this(msg: String) = this(msg, null);

  def stackTraceToString = {
    val trace = getStackTrace
    val sb = new StringBuilder
    for (i ← 0 until trace.length)
      sb.append("\tat %s\n" format trace(i))
    sb.toString
  }
}

object AkkaException {
  val hostname = try {
    InetAddress.getLocalHost.getHostAddress
  } catch {
    case e: UnknownHostException ⇒ "unknown"
  }
}
