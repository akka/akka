/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import akka.io.dns.protocol.{ DnsRecordType, DnsResourceRecord }

import scala.collection.immutable

/**
 * Supersedes [[akka.io.Dns]] protocol.
 *
 * Note that one MUST configure `akka.io.dns.resolver = async` to make use of this protocol and resolver.
 *
 * Allows for more detailed lookups, by specifying which records should be checked,
 * and responses can more information than plain IP addresses (e.g. ports for SRV records).
 */
object DnsProtocol {

  def resolve(name: String): Resolve =
    Resolve(name, NormalLookupRecordTypes)

  def resolve(name: String, recordTypes: Set[DnsRecordType]): Resolve =
    Resolve(name, recordTypes)

  sealed trait Protocol
  private[akka] final case class Resolve(name: String, mode: Set[DnsRecordType]) extends Protocol

  final case class Resolved(name: String, results: immutable.Seq[DnsResourceRecord]) extends Protocol

  import DnsRecordType._
  /** The default set of record types most applications are interested in: A, AAAA and CNAME */
  final val NormalLookupRecordTypes = Set(A, AAAA, CNAME)

  /** Request lookups of `SRV` records */
  final val ServiceRecordTypes = Set(SRV)

}

