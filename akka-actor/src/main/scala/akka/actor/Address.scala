/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import java.net.URI
import java.net.URISyntaxException

/**
 * The address specifies the physical location under which an Actor can be
 * reached. Examples are local addresses, identified by the ActorSystem’s
 * name, and remote addresses, identified by protocol, host and port.
 *
 * This class is final to allow use as a case class (copy method etc.); if
 * for example a remote transport would want to associate additional
 * information with an address, then this must be done externally.
 */
final case class Address(protocol: String, system: String, host: Option[String], port: Option[Int]) {

  def this(protocol: String, system: String) = this(protocol, system, None, None)
  def this(protocol: String, system: String, host: String, port: Int) = this(protocol, system, Option(host), Some(port))

  @transient
  override lazy val toString = {
    val sb = new StringBuilder(protocol)
    sb.append("://")
    sb.append(hostPort)
    sb.toString
  }

  @transient
  lazy val hostPort = {
    val sb = new StringBuilder(system)
    if (host.isDefined) {
      sb.append('@')
      sb.append(host.get)
    }
    if (port.isDefined) {
      sb.append(':')
      sb.append(port.get)
    }
    sb.toString
  }
}

object Address {
  def apply(protocol: String, system: String) = new Address(protocol, system)
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

object AddressExtractor {
  def unapply(addr: String): Option[Address] = {
    try {
      val uri = new URI(addr)
      if (uri.getScheme == null || (uri.getUserInfo == null && uri.getHost == null)) None
      else {
        val addr = Address(uri.getScheme, if (uri.getUserInfo != null) uri.getUserInfo else uri.getHost,
          if (uri.getUserInfo == null || uri.getHost == null) None else Some(uri.getHost),
          if (uri.getPort < 0) None else Some(uri.getPort))
        Some(addr)
      }
    } catch {
      case _: URISyntaxException ⇒ None
    }
  }
}

object ActorPathExtractor {
  def unapply(addr: String): Option[(Address, Iterable[String])] = {
    try {
      val uri = new URI(addr)
      if (uri.getScheme == null || (uri.getUserInfo == null && uri.getHost == null) || uri.getPath == null) None
      else {
        val addr = Address(uri.getScheme, if (uri.getUserInfo != null) uri.getUserInfo else uri.getHost,
          if (uri.getUserInfo == null || uri.getHost == null) None else Some(uri.getHost),
          if (uri.getPort < 0) None else Some(uri.getPort))
        Some((addr, ActorPath.split(uri.getPath).drop(1)))
      }
    } catch {
      case _: URISyntaxException ⇒ None
    }
  }
}