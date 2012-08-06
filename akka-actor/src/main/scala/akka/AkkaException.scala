/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

/**
 * Akka base Exception. Each Exception gets:
 * <ul>
 *   <li>a uuid for tracking purposes</li>
 *   <li>toString that includes exception name, message and uuid</li>
 * </ul>
 */
@SerialVersionUID(1L)
class AkkaException(message: String, cause: Throwable) extends RuntimeException(message, cause) with Serializable {
  def this(msg: String) = this(msg, null)

  lazy val uuid: String = java.util.UUID.randomUUID().toString

  override def toString(): String = uuid + super.toString()
}

/**
 * This exception is thrown when Akka detects a problem with the provided configuration
 */
class ConfigurationException(message: String, cause: Throwable) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null)
}
