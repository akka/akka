/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery

import java.net.InetAddress
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

import scala.collection.immutable
import scala.compat.java8.OptionConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.actor.{ DeadLetterSuppression, NoSerializationVerificationNeeded }
import akka.util.HashCode

object ServiceDiscovery {

  object Resolved {
    def apply(serviceName: String, addresses: immutable.Seq[ResolvedTarget]): Resolved =
      new Resolved(serviceName, addresses)

    def unapply(resolved: Resolved): Option[(String, immutable.Seq[ResolvedTarget])] =
      Some((resolved.serviceName, resolved.addresses))
  }

  /** Result of a successful resolve request */
  final class Resolved(val serviceName: String, val addresses: immutable.Seq[ResolvedTarget])
      extends DeadLetterSuppression
      with NoSerializationVerificationNeeded {

    /**
     * Java API
     */
    def getAddresses: java.util.List[ResolvedTarget] = {
      import akka.util.ccompat.JavaConverters._
      addresses.asJava
    }

    override def toString: String = s"Resolved($serviceName,$addresses)"

    override def equals(obj: Any): Boolean = obj match {
      case other: Resolved => serviceName == other.serviceName && addresses == other.addresses
      case _               => false
    }

    override def hashCode(): Int = {
      var result = HashCode.SEED
      result = HashCode.hash(result, serviceName)
      result = HashCode.hash(result, addresses)
      result
    }

  }

  object ResolvedTarget {
    // Simply compare the bytes of the address.
    // This may not work in exotic cases such as IPv4 addresses encoded as IPv6 addresses.
    import com.github.ghik.silencer.silent
    @silent
    private implicit val inetAddressOrdering: Ordering[InetAddress] =
      Ordering.by[InetAddress, Iterable[Byte]](_.getAddress)

    implicit val addressOrdering: Ordering[ResolvedTarget] = Ordering.by { t =>
      (t.address, t.host, t.port)
    }

    /**
     * @param host the hostname or the IP address of the target
     * @param port optional port number
     * @param address IP address of the target. This is used during cluster bootstap when available.
     */
    def apply(host: String, port: Option[Int], address: Option[InetAddress]): ResolvedTarget =
      new ResolvedTarget(host, port, address)
  }

  /**
   * Resolved target host, with optional port and the IP address.
   * @param host the hostname or the IP address of the target
   * @param port optional port number
   * @param address optional IP address of the target. This is used during cluster bootstap when available.
   */
  final class ResolvedTarget(val host: String, val port: Option[Int], val address: Option[InetAddress])
      extends NoSerializationVerificationNeeded {

    /**
     * Java API
     */
    def getPort: Optional[Int] =
      port.asJava

    /**
     * Java API
     */
    def getAddress: Optional[InetAddress] =
      address.asJava

    override def toString: String = s"ResolvedTarget($host,$port,$address)"

    override def equals(obj: Any): Boolean = obj match {
      case other: ResolvedTarget => host == other.host && port == other.port && address == other.address
      case _                     => false
    }

    override def hashCode(): Int = {
      var result = HashCode.SEED
      result = HashCode.hash(result, host)
      result = HashCode.hash(result, port)
      result = HashCode.hash(result, address)
      result
    }

  }

}

/**
 * A service lookup. It is up to each method to decide
 * what to do with the optional portName and protocol fields.
 * For example `portName` could be used to distinguish between
 * Akka remoting ports and HTTP ports.
 *
 * @param serviceName must not be 'null' or an empty String
 */
final class Lookup(val serviceName: String, val portName: Option[String], val protocol: Option[String])
    extends NoSerializationVerificationNeeded {

  require(serviceName != null, "'serviceName' cannot be null")
  require(serviceName.trim.nonEmpty, "'serviceName' cannot be empty")

  /**
   * Which port for a service e.g. Akka remoting or HTTP.
   * Maps to "service" for an SRV records.
   */
  def withPortName(value: String): Lookup = copy(portName = Some(value))

  /**
   * Which protocol e.g. TCP or UDP.
   * Maps to "protocol" for SRV records.
   */
  def withProtocol(value: String): Lookup = copy(protocol = Some(value))

  /**
   * Java API
   */
  def getPortName: Optional[String] =
    portName.asJava

  /**
   * Java API
   */
  def getProtocol: Optional[String] =
    protocol.asJava

  private def copy(
      serviceName: String = serviceName,
      portName: Option[String] = portName,
      protocol: Option[String] = protocol): Lookup =
    new Lookup(serviceName, portName, protocol)

  override def toString: String = s"Lookup($serviceName,$portName,$protocol)"

  override def equals(obj: Any): Boolean = obj match {
    case other: Lookup => serviceName == other.serviceName && portName == other.portName && protocol == other.protocol
    case _             => false
  }

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, serviceName)
    result = HashCode.hash(result, portName)
    result = HashCode.hash(result, protocol)
    result
  }

}

case object Lookup {

  /**
   * Create a service Lookup with only a serviceName.
   * Use withPortName and withProtocol to provide optional portName
   * and protocol
   */
  def apply(serviceName: String): Lookup = new Lookup(serviceName, None, None)

  /**
   * Create a service Lookup with `serviceName`, optional `portName` and optional `protocol`.
   */
  def apply(serviceName: String, portName: Option[String], protocol: Option[String]): Lookup =
    new Lookup(serviceName, portName, protocol)

  /**
   * Java API
   *
   * Create a service Lookup with only a serviceName.
   * Use withPortName and withProtocol to provide optional portName
   * and protocol
   */
  def create(serviceName: String): Lookup = new Lookup(serviceName, None, None)

  private val SrvQuery = """^_(.+?)\._(.+?)\.(.+?)$""".r

  /**
   * Validates domain name:
   * (as defined in https://tools.ietf.org/html/rfc1034)
   *
   * - a label has 1 to 63 chars
   * - valid chars for a label are: a-z, A-Z, 0-9 and -
   * - a label can't start with a 'hyphen' (-)
   * - a label can't start with a 'digit' (0-9)
   * - a label can't end with a 'hyphen' (-)
   * - labels are separated by a 'dot' (.)
   *
   * Starts with a label:
   * Label Pattern: (?![0-9-])[A-Za-z0-9-]{1,63}(?<!-)
   *      (?![0-9-]) => negative look ahead, first char can't be hyphen (-) or digit (0-9)
   *      [A-Za-z0-9-]{1,63} => digits, letters and hyphen, from 1 to 63
   *      (?<!-) => negative look behind, last char can't be hyphen (-)
   *
   * A label can be followed by other labels:
   *    Pattern: (\.(?![0-9-])[A-Za-z0-9-]{1,63}(?<!-)))*
   *      . => separated by a . (dot)
   *      label pattern => (?![0-9-])[A-Za-z0-9-]{1,63}(?<!-)
   *      * => match zero or more times
   */
  private val DomainName = "^((?![0-9-])[A-Za-z0-9-]{1,63}(?<!-))((\\.(?![0-9-])[A-Za-z0-9-]{1,63}(?<!-)))*$".r

  /**
   * Create a service Lookup from a string with format:
   * _portName._protocol.serviceName.
   * (as specified by https://www.ietf.org/rfc/rfc2782.txt)
   *
   * If the passed string conforms with this format, a SRV Lookup is returned.
   * The serviceName part must be a valid domain name.
   * (as defined in https://tools.ietf.org/html/rfc1034)
   *
   * The string is parsed and dismembered to build a Lookup as following:
   * Lookup(serviceName).withPortName(portName).withProtocol(protocol)
   *
   * @throws java.lang.NullPointerException If the passed string is null
   * @throws java.lang.IllegalArgumentException If the string doesn't not conform with the SRV format
   */
  def parseSrv(str: String): Lookup =
    str match {
      case SrvQuery(portName, protocol, serviceName) if validDomainName(serviceName) =>
        Lookup(serviceName).withPortName(portName).withProtocol(protocol)

      case null =>
        throw new NullPointerException("Unable to create Lookup from passed SRV string. Passed value is 'null'")
      case _ =>
        throw new IllegalArgumentException(s"Unable to create Lookup from passed SRV string, invalid format: $str")
    }

  /**
   * Returns true if passed string conforms with SRV format. Otherwise returns false.
   */
  def isValidSrv(srv: String): Boolean =
    srv match {
      case SrvQuery(_, _, serviceName) => validDomainName(serviceName)
      case _                           => false
    }

  private def validDomainName(name: String): Boolean =
    DomainName.pattern.asPredicate().test(name)

}

/**
 * Implement to provide a service discovery method
 *
 */
abstract class ServiceDiscovery {

  import ServiceDiscovery._

  /**
   * Scala API: Perform lookup using underlying discovery implementation.
   *
   * @param lookup       A service discovery lookup.
   * @param resolveTimeout Timeout. Up to the discovery-method to adhere to his
   */
  def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved]

  /**
   * Scala API: Perform lookup using underlying discovery implementation.
   *
   * Convenience for when only a name is required.
   */
  def lookup(serviceName: String, resolveTimeout: FiniteDuration): Future[Resolved] =
    lookup(Lookup(serviceName), resolveTimeout)

  /**
   * Java API: Perform basic lookup using underlying discovery implementation.
   *
   * While the implementation may provide other settings and ways to configure timeouts,
   * the passed `resolveTimeout` should never be exceeded, as it signals the application's
   * eagerness to wait for a result for this specific lookup.
   *
   * The returned future SHOULD be failed once resolveTimeout has passed.
   *
   */
  def lookup(query: Lookup, resolveTimeout: java.time.Duration): CompletionStage[Resolved] = {
    import scala.compat.java8.FutureConverters._
    lookup(query, FiniteDuration(resolveTimeout.toMillis, TimeUnit.MILLISECONDS)).toJava
  }

  /**
   * Java API
   *
   * @param serviceName           A name, see discovery-method's docs for how this is interpreted
   * @param resolveTimeout Timeout. Up to the discovery-methodto adhere to his
   */
  def lookup(serviceName: String, resolveTimeout: java.time.Duration): CompletionStage[Resolved] =
    lookup(Lookup(serviceName), resolveTimeout)

}
