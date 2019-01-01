/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery

import java.net.InetAddress
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

import akka.actor.DeadLetterSuppression
import akka.annotation.ApiMayChange

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

@ApiMayChange
object ServiceDiscovery {

  /** Result of a successful resolve request */
  final case class Resolved(serviceName: String, addresses: immutable.Seq[ResolvedTarget])
    extends DeadLetterSuppression {

    /**
     * Java API
     */
    def getAddresses: java.util.List[ResolvedTarget] = {
      import scala.collection.JavaConverters._
      addresses.asJava
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

    def apply(host: String, port: Option[Int]): ResolvedTarget =
      ResolvedTarget(host, port, Try(InetAddress.getByName(host)).toOption)
  }

  /**
   * Resolved target host, with optional port and the IP address.
   * @param host the hostname or the IP address of the target
   * @param port optional port number
   * @param address optional IP address of the target. This is used during cluster bootstap when available.
   */
  final case class ResolvedTarget(
    host:    String,
    port:    Option[Int],
    address: Option[InetAddress]
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
final case class Lookup(serviceName: String, portName: Option[String], protocol: Option[String]) {

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
