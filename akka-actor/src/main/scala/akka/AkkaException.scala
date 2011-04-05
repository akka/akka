/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka

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
@serializable abstract class AkkaException(message: String = "") extends {
  val exceptionName = getClass.getName
  val uuid = "%s_%s".format(AkkaException.hostname, newUuid)
} with RuntimeException(message) {
  override lazy val toString = "%s\n\t[%s]\n\t%s".format(exceptionName, uuid, message)
}

object AkkaException {
  val hostname = try {
    InetAddress.getLocalHost.getHostName
  } catch {
    case e: UnknownHostException => "unknown"
  }
}
