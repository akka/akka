/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import java.net.URI
import java.net.URISyntaxException
import java.net.MalformedURLException

/**
 * The address specifies the physical location under which an Actor can be
 * reached. Examples are local addresses, identified by the ActorSystem’s
 * name, and remote addresses, identified by protocol, host and port.
 *
 * This class is final to allow use as a case class (copy method etc.); if
 * for example a remote transport would want to associate additional
 * information with an address, then this must be done externally.
 */
final case class Address private (protocol: String, system: String, host: Option[String], port: Option[Int]) {

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
  def apply(protocol: String, system: String, host: String, port: Int) = new Address(protocol, system, Some(host), Some(port))
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

/**
 * This object serves as extractor for Scala and as address parser for Java.
 */
object AddressExtractor {
  def unapply(addr: String): Option[Address] =
    try {
      val uri = new URI(addr)
      unapply(uri)
    } catch {
      case _: URISyntaxException ⇒ None
    }

  def unapply(uri: URI): Option[Address] =
    if (uri.getScheme == null || (uri.getUserInfo == null && uri.getHost == null)) None
    else if (uri.getUserInfo == null) { // case 1: “akka://system”
      if (uri.getPort != -1) None
      else Some(Address(uri.getScheme, uri.getHost))
    } else { // case 2: “akka://system@host:port”
      if (uri.getHost == null || uri.getPort == -1) None
      else Some(
        if (uri.getUserInfo == null) Address(uri.getScheme, uri.getHost)
        else Address(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort))
    }

  /**
   * Try to construct an Address from the given String or throw a java.net.MalformedURLException.
   */
  def apply(addr: String): Address = addr match {
    case AddressExtractor(address) ⇒ address
    case _                         ⇒ throw new MalformedURLException
  }

  /**
   * Java API: Try to construct an Address from the given String or throw a java.net.MalformedURLException.
   */
  def parse(addr: String): Address = apply(addr)
}

object ActorPathExtractor {
  def unapply(addr: String): Option[(Address, Iterable[String])] =
    try {
      val uri = new URI(addr)
      if (uri.getPath == null) None
      else AddressExtractor.unapply(uri) match {
        case None       ⇒ None
        case Some(addr) ⇒ Some((addr, ActorPath.split(uri.getPath).drop(1)))
      }
    } catch {
      case _: URISyntaxException ⇒ None
    }
}