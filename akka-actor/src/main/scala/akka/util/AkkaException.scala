/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka

import akka.util.Logging
import akka.actor.newUuid

import java.io.{StringWriter, PrintWriter}
import java.net.{InetAddress, UnknownHostException}

/**
 * Akka base Exception. Each Exception gets:
 * <ul>
 *   <li>a UUID for tracking purposes</li>
 *   <li>a message including exception name, uuid, original message and the stacktrace</li>
 *   <li>a method 'log' that will log the exception once and only once</li>
 * </ul>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable abstract class AkkaException(message: String) extends RuntimeException(message) {
  import AkkaException._
  val exceptionName = getClass.getName

  val uuid = "%s_%s".format(hostname, newUuid)

  override val toString = "%s\n\t[%s]\n\t%s\n\t%s".format(exceptionName, uuid, message, stackTrace)

  val stackTrace = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    printStackTrace(pw)
    sw.toString
  }

  private lazy val _log = {
    AkkaException.log.slf4j.error(toString)
    ()
  }

  def log: Unit = _log
}

object AkkaException extends Logging {
  val hostname = try {
    InetAddress.getLocalHost.getHostName
  } catch {
    case e: UnknownHostException => "unknown"
  }
}
