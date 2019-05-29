/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.util

import akka.actor.NoSerializationVerificationNeeded

import scala.collection.{ immutable => im }
import akka.util.ccompat.JavaConverters._

/**
 * Supersedes [[akka.io.Dns]] protocol.
 *
 * Note that one MUST configure `akka.io.dns.resolver = async` to make use of this protocol and resolver.
 *
 * Allows for more detailed lookups, by specifying which records should be checked,
 * and responses can more information than plain IP addresses (e.g. ports for SRV records).
 *
 */
object DnsProtocol {

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
  }

  /**
   * Java API
   */
  def resolve(name: String): Resolve = Resolve(name, Ip())

  /**
   * Java API
   */
  def resolve(name: String, requestType: RequestType): Resolve = Resolve(name, requestType)

  /**
   * @param name of the record
   * @param records resource records for the query
   * @param additionalRecords records that relate to the query but are not strictly answers
   */
  final case class Resolved(name: String, records: im.Seq[ResourceRecord], additionalRecords: im.Seq[ResourceRecord])
      extends NoSerializationVerificationNeeded {

    /**
     * Java API
     *
     * Records for the query
     */
    def getRecords(): util.List[ResourceRecord] = records.asJava

    /**
     * Java API
     *
     * Records that relate to the query but are not strickly answers e.g. A records for the records returned for an SRV query.
     *
     */
    def getAdditionalRecords(): util.List[ResourceRecord] = additionalRecords.asJava
  }

  object Resolved {

    def apply(name: String, records: im.Seq[ResourceRecord]): Resolved =
      new Resolved(name, records, Nil)
  }

}
