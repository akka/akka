/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

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
 *   <li>toString that includes exception name, message and uuid</li>
 *   <li>toLongString which also includes the stack trace</li>
 * </ul>
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
class AkkaException(message: String = "", cause: Throwable = null) extends RuntimeException(message, cause) with Serializable {
  lazy val uuid = java.util.UUID.randomUUID().toString

  override lazy val toString =
    "%s:%s\n[%s]".format(getClass.getName, message, uuid)

  lazy val toLongString =
    "%s:%s\n[%s]\n%s".format(getClass.getName, message, uuid, stackTraceToString)

  def this(msg: String) = this(msg, null)

  def stackTraceToString = AkkaException.stackTraceToString(this)
}

/**
 * This exception is thrown when Akka detects a problem with the provided configuration
 */
class ConfigurationException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null)
}
