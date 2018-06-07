/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 * Adopted from Apache v2 licensed: https://github.com/ilya-epifanov/akka-dns
 */

package akka.io.dns.protocol

import akka.util.{ ByteIterator, ByteString, ByteStringBuilder }

/** INTERNAL API */
final case class DnsQuestion(name: String, qType: DnsRecordType, qClass: DnsRecordClass) {
  def write(out: ByteStringBuilder) {
    DnsDomainName.write(out, name)
    DnsRecordType.write(out, qType)
    DnsRecordClass.write(out, qClass)
  }
}

object DnsQuestion {
  def parse(it: ByteIterator, msg: ByteString): DnsQuestion = {
    val name = DnsDomainName.parse(it, msg)
    val qType = DnsRecordType.parse(it)
    val qClass = DnsRecordClass.parse(it)
    DnsQuestion(name, qType, qClass)
  }
}
