/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.protocol

import akka.annotation.InternalApi
import akka.util.{ ByteIterator, ByteStringBuilder, OptionVal }

/**
 * DNS Record Type
 */
final case class DnsRecordType(code: Short, name: String)

object DnsRecordType {

  // array for fast lookups by id
  // wasteful, but we get trivial indexing into it for lookup
  private final val lookupTable = Array.ofDim[DnsRecordType](256)

  /** INTERNAL API: TODO move it somewhere off the datatype */
  // TODO other type than ByteStringBuilder? (was used in akka-dns)
  def write(out: ByteStringBuilder, value: DnsRecordType): Unit = {
    out.putShort(value.code)
  }

  /** INTERNAL API: TODO move it somewhere off the datatype */
  def parse(it: ByteIterator): DnsRecordType = {
    val id = it.getShort
    apply(id) match {
      case OptionVal.None    ⇒ throw new IllegalArgumentException(s"Illegal id [${id}] for DnsRecordType")
      case OptionVal.Some(t) ⇒ t
    }
  }

  private def register(t: DnsRecordType) = {
    lookupTable(t.code) = t
    t
  }

  def apply(id: Short): OptionVal[DnsRecordType] = {
    if (id < 1 || id > 255) OptionVal.None
    else OptionVal(lookupTable(id))
  }

  /** A host address */
  final val A = register(new DnsRecordType(1, "A"))
  /** An authoritative name server */
  final val NS = register(new DnsRecordType(2, "NS"))
  /** A mail destination (Obsolete - use MX) */
  final val MD = register(new DnsRecordType(3, "MD"))
  /** A mail forwarder (Obsolete - use MX) */
  final val MF = register(new DnsRecordType(4, "MF"))
  /** the canonical name for an alias */
  final val CNAME = register(new DnsRecordType(5, "CNAME"))
  /** marks the start of a zone of authority */
  final val SOA = register(new DnsRecordType(6, "SOA"))
  /** A mailbox domain name (EXPERIMENTAL) */
  final val MB = register(new DnsRecordType(7, "MB"))
  /** A mail group member (EXPERIMENTAL) */
  final val MG = register(new DnsRecordType(8, "MG"))
  /** A mail rename domain name (EXPERIMENTAL) */
  final val MR = register(new DnsRecordType(9, "MR"))
  /** A null RR (EXPERIMENTAL) */
  final val NULL = register(new DnsRecordType(10, "NULL"))
  /** A well known service description */
  final val WKS = register(new DnsRecordType(11, "WKS"))
  /** A domain name pointer */
  final val PTR = register(new DnsRecordType(12, "PTR"))
  /** host information */
  final val HINFO = register(new DnsRecordType(13, "HINFO"))
  /** mailbox or mail list information */
  final val MINFO = register(new DnsRecordType(14, "MINFO"))
  /** mail exchange */
  final val MX = register(new DnsRecordType(15, "MX"))
  /** text strings */
  final val TXT = register(new DnsRecordType(16, "TXT"))

  /** The AAAA resource record type is a record specific to the Internet class that stores a single IPv6 address. */
  // See: https://tools.ietf.org/html/rfc3596
  final val AAAA = register(new DnsRecordType(28, "AAAA"))

  /**
   * The SRV RR allows administrators to use several servers for a single
   * domain, to move services from host to host with little fuss, and to
   * designate some hosts as primary servers for a service and others as
   * backups.
   */
  // See: https://tools.ietf.org/html/rfc2782
  final val SRV = register(new DnsRecordType(33, "SRV"))

  final val AXFR = register(new DnsRecordType(252, "AXFR"))
  final val MAILB = register(new DnsRecordType(253, "MAILB"))
  final val MAILA = register(new DnsRecordType(254, "MAILA"))
  final val WILDCARD = register(new DnsRecordType(255, "WILDCARD"))
}
