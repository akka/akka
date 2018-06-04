/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 * Adopted from Apache v2 licensed: https://github.com/ilya-epifanov/akka-dns
 */

package akka.io.dns.protocol

import akka.util.{ ByteIterator, ByteStringBuilder }

case class DnsRecordClass(code: Short, name: String)

object DnsRecordClass {

  val IN = DnsRecordClass(1, "IN")
  val CS = DnsRecordClass(2, "CS")
  val CH = DnsRecordClass(3, "CH")
  val HS = DnsRecordClass(4, "HS")

  val WILDCARD = DnsRecordClass(255, "WILDCARD")

  def parse(it: ByteIterator): DnsRecordClass = {
    it.getShort match {
      case 1   ⇒ IN
      case 2   ⇒ CS
      case 3   ⇒ CH
      case 255 ⇒ WILDCARD
      case _   ⇒ ??? // FIXME
    }
  }

  def write(out: ByteStringBuilder, c: DnsRecordClass): Unit = {
    out.putShort(c.code)
  }
}
