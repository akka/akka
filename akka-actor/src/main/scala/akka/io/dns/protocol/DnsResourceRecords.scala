/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 * Adopted from Apache v2 licensed: https://github.com/ilya-epifanov/akka-dns
 */

package akka.io.dns.protocol

import java.net.{ Inet4Address, Inet6Address, InetAddress }

import akka.annotation.InternalApi
import akka.util.{ ByteIterator, ByteString, ByteStringBuilder }

import scala.annotation.switch

@InternalApi
sealed abstract class DnsResourceRecord(val name: String, val ttl: Int, val recType: Short, val recClass: Short) {
  def write(it: ByteStringBuilder): Unit = {
    DnsDomainName.write(it, name)
    it.putShort(recType)
    it.putShort(recClass)
  }
}

@InternalApi
final case class ARecord(override val name: String, override val ttl: Int,
                         ip: Inet4Address) extends DnsResourceRecord(name, ttl, DnsRecordType.A.code, DnsRecordClass.IN.code) {
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    val addr = ip.getAddress
    it.putShort(addr.length)
    it.putBytes(addr)
  }
}

@InternalApi
object ARecord {
  def parseBody(name: String, ttl: Int, length: Short, it: ByteIterator): ARecord = {
    val addr = Array.ofDim[Byte](4)
    it.getBytes(addr)
    ARecord(name, ttl, InetAddress.getByAddress(addr).asInstanceOf[Inet4Address])
  }
}

@InternalApi
final case class AAAARecord(override val name: String, override val ttl: Int,
                            ip: Inet6Address) extends DnsResourceRecord(name, ttl, DnsRecordType.AAAA.code, DnsRecordClass.IN.code) {
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    val addr = ip.getAddress
    it.putShort(addr.length)
    it.putBytes(addr)
  }
}

@InternalApi
object AAAARecord {
  def parseBody(name: String, ttl: Int, length: Short, it: ByteIterator): AAAARecord = {
    val addr = Array.ofDim[Byte](16)
    it.getBytes(addr)
    AAAARecord(name, ttl, InetAddress.getByAddress(addr).asInstanceOf[Inet6Address])
  }
}

@InternalApi
final case class CNAMERecord(override val name: String, override val ttl: Int,
                             canonicalName: String) extends DnsResourceRecord(name, ttl, DnsRecordType.CNAME.code, DnsRecordClass.IN.code) {
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    it.putShort(DnsDomainName.length(name))
    DnsDomainName.write(it, name)
  }
}

@InternalApi
object CNAMERecord {
  def parseBody(name: String, ttl: Int, length: Short, it: ByteIterator, msg: ByteString): CNAMERecord = {
    CNAMERecord(name, ttl, DnsDomainName.parse(it, msg))
  }
}

@InternalApi
final case class SRVRecord(override val name: String, override val ttl: Int,
                           priority: Int, weight: Int, port: Int, target: String) extends DnsResourceRecord(name, ttl, DnsRecordType.SRV.code, DnsRecordClass.IN.code) {
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    it.putShort(priority)
    it.putShort(weight)
    it.putShort(port)
    DnsDomainName.write(it, target)
  }
}

@InternalApi
object SRVRecord {
  def parseBody(name: String, ttl: Int, length: Short, it: ByteIterator, msg: ByteString): SRVRecord = {
    val priority = it.getShort
    val weight = it.getShort
    val port = it.getShort
    SRVRecord(name, ttl, priority, weight, port, DnsDomainName.parse(it, msg))
  }
}

@InternalApi
final case class UnknownRecord(override val name: String, override val ttl: Int,
                               override val recType: Short, override val recClass: Short,
                               data: ByteString) extends DnsResourceRecord(name, ttl, recType, recClass) {
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    it.putShort(data.length)
    it.append(data)
  }
}

@InternalApi
object UnknownRecord {
  def parseBody(name: String, ttl: Int, recType: Short, recClass: Short, length: Short, it: ByteIterator): UnknownRecord =
    UnknownRecord(name, ttl, recType, recClass, it.toByteString)
}

@InternalApi
object DnsResourceRecord {
  def parse(it: ByteIterator, msg: ByteString): DnsResourceRecord = {
    val name = DnsDomainName.parse(it, msg)
    val recType = it.getShort
    val recClass = it.getShort
    val ttl = it.getInt
    val rdLength = it.getShort
    val data = it.clone().take(rdLength)
    it.drop(rdLength)
    (recType: @switch) match {
      case 1  ⇒ ARecord.parseBody(name, ttl, rdLength, data)
      case 5  ⇒ CNAMERecord.parseBody(name, ttl, rdLength, data, msg)
      case 28 ⇒ AAAARecord.parseBody(name, ttl, rdLength, data)
      case 33 ⇒ SRVRecord.parseBody(name, ttl, rdLength, data, msg)
      case _  ⇒ UnknownRecord.parseBody(name, ttl, recType, recClass, rdLength, data)
    }
  }
}

