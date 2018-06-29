/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.util

import akka.actor.NoSerializationVerificationNeeded
import akka.annotation.ApiMayChange

import scala.collection.immutable
import scala.collection.JavaConverters._

/**
 * Supersedes [[akka.io.Dns]] protocol.
 *
 * Note that one MUST configure `akka.io.dns.resolver = async` to make use of this protocol and resolver.
 *
 * Allows for more detailed lookups, by specifying which records should be checked,
 * and responses can more information than plain IP addresses (e.g. ports for SRV records).
 *
 */
@ApiMayChange
object DnsProtocol {

  @ApiMayChange
  sealed trait RequestType
  final case class Ip(ipv4: Boolean = true, ipv6: Boolean = true) extends RequestType
  final case object Srv extends RequestType

  /**
   * Java API
   */
  def ipRequestType(ipv4: Boolean, ipv6: Boolean): RequestType = Ip(ipv4, ipv6)

  /**
   * Java API
   */
  def ipRequestType(): RequestType = Ip(ipv4 = true, ipv6 = true)

  /**
   * Java API
   */
  def srvRequestType(): RequestType = Srv

  final case class Resolve(name: String, requestType: RequestType)

  object Resolve {
    def apply(name: String): Resolve = Resolve(name, Ip())

    /**
     * Java API
     */
    def create(name: String): Resolve = Resolve(name, Ip())

    /**
     * Java API
     */
    def create(name: String, requestType: RequestType): Resolve = Resolve(name, requestType)
  }

  @ApiMayChange
  final case class Resolved(name: String, results: immutable.Seq[ResourceRecord]) extends NoSerializationVerificationNeeded {
    /**
     * Java API
     */
    def getResults(): util.List[ResourceRecord] = results.asJava
  }

}

