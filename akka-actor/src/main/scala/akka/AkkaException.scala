/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import akka.actor.newUuid
import java.net.{ InetAddress, UnknownHostException }
import akka.actor.ActorSystem

object AkkaException {

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
 *   <li>system address information</li>
 *   <li>toString that includes exception name, message and uuid</li>
 *   <li>toLongString which also includes the stack trace</li>
 * </ul>
 *
 * @param message detailed failure description
 * @param cause another exception that was causing the problem, optional (null is allowed)
 * @param system to include system address in exception string representation, optional (null is allowed)
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
class AkkaException(message: String = "", cause: Throwable, system: ActorSystem) extends RuntimeException(message, cause) with Serializable {
  lazy val uuid: String = if (system eq null) newUuid.toString else "%s:%s".format(system.toString, newUuid)

  override lazy val toString: String =
    "%s:%s\n[%s]".format(getClass.getName, message, uuid)

  lazy val toLongString: String =
    "%s:%s\n[%s]\n%s".format(getClass.getName, message, uuid, stackTraceToString)

  def this(msg: String) = this(msg, null, null)

  def this(msg: String, system: ActorSystem) = this(msg, null, system)

  // TODO def this(msg: String, cause: Throwable) = this(msg, cause, null)

  def stackTraceToString: String = AkkaException.stackTraceToString(this)
}
