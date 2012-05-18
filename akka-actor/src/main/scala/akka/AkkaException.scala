/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

object AkkaException {
  //FIXME DOC
  def toStringWithStackTrace(throwable: Throwable): String = throwable match {
    case null              ⇒ "Unknown Throwable: was 'null'"
    case ae: AkkaException ⇒ ae.toLongString
    case e                 ⇒ "%s:%s\n%s" format (e.getClass.getName, e.getMessage, stackTraceToString(e))
  }

  /**
   * Returns the given Throwables stack trace as a String, or the empty String if no trace is found
   * @param throwable
   * @return
   */
  def stackTraceToString(throwable: Throwable): String = throwable.getStackTrace match {
    case null               ⇒ ""
    case x if x.length == 0 ⇒ ""
    case trace ⇒
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
  def this(msg: String) = this(msg, null)

  lazy val uuid = java.util.UUID.randomUUID().toString

  override def toString: String = "%s:%s\n[%s]".format(getClass.getName, message, uuid)

  def toLongString: String = "%s:%s\n[%s]\n%s".format(getClass.getName, message, uuid, stackTraceToString)

  def stackTraceToString: String = AkkaException.stackTraceToString(this)
}

/**
 * This exception is thrown when Akka detects a problem with the provided configuration
 */
class ConfigurationException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null)
}
