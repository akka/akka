/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery

import java.net.InetAddress
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.actor.DeadLetterSuppression
import akka.annotation.ApiMayChange
import akka.util.HashCode

@ApiMayChange
object ServiceDiscovery {

  object Resolved {
    def apply(serviceName: String, addresses: immutable.Seq[ResolvedTarget]): Resolved =
      new Resolved(serviceName, addresses)

    def unapply(resolved: Resolved): Option[(String, immutable.Seq[ResolvedTarget])] =
      Some((resolved.serviceName, resolved.addresses))
  }

  /** Result of a successful resolve request */
  final class Resolved(val serviceName: String, val addresses: immutable.Seq[ResolvedTarget])
    extends DeadLetterSuppression {

    /**
     * Java API
     */
    def getAddresses: java.util.List[ResolvedTarget] = {
      import scala.collection.JavaConverters._
      addresses.asJava
    }

    override def toString: String = s"Resolved($serviceName,$addresses)"

    override def equals(obj: Any): Boolean = obj match {
      case other: Resolved ⇒ serviceName == other.serviceName && addresses == other.addresses
      case _               ⇒ false
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
    private implicit val inetAddressOrdering: Ordering[InetAddress] =
      Ordering.by[InetAddress, Iterable[Byte]](_.getAddress)

    implicit val addressOrdering: Ordering[ResolvedTarget] = Ordering.by { t ⇒
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
  final class ResolvedTarget(
    val host:    String,
    val port:    Option[Int],
    val address: Option[InetAddress]
  ) {

    /**
     * Java API
     */
    def getPort: Optional[Int] = {
      import scala.compat.java8.OptionConverters._
      port.asJava
    }

    /**
     * Java API
     */
    def getAddress: Optional[InetAddress] = {
      import scala.compat.java8.OptionConverters._
      address.asJava
    }

    override def toString: String = s"ResolvedTarget($host,$port,$address)"

    override def equals(obj: Any): Boolean = obj match {
      case other: ResolvedTarget ⇒ host == other.host && port == other.port && address == other.address
      case _                     ⇒ false
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
 */
@ApiMayChange
final class Lookup(
  val serviceName: String,
  val portName:    Option[String],
  val protocol:    Option[String]) {

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

  private def copy(
    serviceName: String         = serviceName,
    portName:    Option[String] = portName,
    protocol:    Option[String] = protocol): Lookup =
    new Lookup(serviceName, portName, protocol)

  override def toString: String = s"Lookup($serviceName,$portName,$protocol)"

  override def equals(obj: Any): Boolean = obj match {
    case other: Lookup ⇒ serviceName == other.serviceName && portName == other.portName && protocol == other.protocol
    case _             ⇒ false
  }

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, serviceName)
    result = HashCode.hash(result, portName)
    result = HashCode.hash(result, protocol)
    result
  }

}

@ApiMayChange
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
}

/**
 * Implement to provide a service discovery method
 *
 */
@ApiMayChange
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
