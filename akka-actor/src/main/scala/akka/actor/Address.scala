/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import java.net.URI
import java.net.URISyntaxException

/**
 * The address specifies the physical location under which an Actor can be
 * reached. Examples are local addresses, identified by the ActorSystem’s
 * name, and remote addresses, identified by protocol, host and port.
 */
abstract class Address {
  def protocol: String
  def hostPort: String
  @transient
  override lazy val toString = protocol + "://" + hostPort
}

case class LocalAddress(systemName: String) extends Address {
  def protocol = "akka"
  def hostPort = systemName
}

object RelativeActorPath {
  def unapply(addr: String): Option[Iterable[String]] = {
    try {
      val uri = new URI(addr)
      if (uri.isAbsolute) None
      else Some(ActorPath.split(uri.getPath))
    }
  }
}

object LocalActorPath {
  def unapply(addr: String): Option[(LocalAddress, Iterable[String])] = {
    try {
      val uri = new URI(addr)
      if (uri.getScheme != "akka" || uri.getUserInfo != null || uri.getHost == null || uri.getPath == null) None
      else Some(LocalAddress(uri.getHost), ActorPath.split(uri.getPath).drop(1))
    } catch {
      case _: URISyntaxException ⇒ None
    }
  }
}