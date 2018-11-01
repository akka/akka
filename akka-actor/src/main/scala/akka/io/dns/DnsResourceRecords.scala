/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.net.{ Inet4Address, Inet6Address, InetAddress }

import akka.actor.NoSerializationVerificationNeeded
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.io.dns.internal.{ DomainName, _ }
import akka.util.{ ByteIterator, ByteString, ByteStringBuilder }

import scala.annotation.switch

@ApiMayChange
sealed abstract class ResourceRecord(val name: String, val ttlSeconds: Int, val recType: Short, val recClass: Short)
  extends NoSerializationVerificationNeeded {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[dns] def write(it: ByteStringBuilder): Unit = {
    DomainName.write(it, name)
    it.putShort(recType)
    it.putShort(recClass)
  }
}

@ApiMayChange
final case class ARecord(override val name: String, override val ttlSeconds: Int,
                         ip: InetAddress) extends ResourceRecord(name, ttlSeconds, RecordType.A.code, RecordClass.IN.code) {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[dns] override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    val addr = ip.getAddress
    it.putShort(addr.length)
    it.putBytes(addr)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[dns] object ARecord {
  def parseBody(name: String, ttlSeconds: Int, length: Short, it: ByteIterator): ARecord = {
    val addr = Array.ofDim[Byte](4)
    it.getBytes(addr)
    ARecord(name, ttlSeconds, InetAddress.getByAddress(addr).asInstanceOf[Inet4Address])
  }
}

@ApiMayChange
final case class AAAARecord(override val name: String, override val ttlSeconds: Int,
                            ip: Inet6Address) extends ResourceRecord(name, ttlSeconds, RecordType.AAAA.code, RecordClass.IN.code) {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[dns] override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    val addr = ip.getAddress
    it.putShort(addr.length)
    it.putBytes(addr)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[dns] object AAAARecord {

  /**
   * INTERNAL API
   */
  @InternalApi
  def parseBody(name: String, ttlSeconds: Int, length: Short, it: ByteIterator): AAAARecord = {
    val addr = Array.ofDim[Byte](16)
    it.getBytes(addr)
    AAAARecord(name, ttlSeconds, InetAddress.getByAddress(addr).asInstanceOf[Inet6Address])
  }
}

@ApiMayChange
final case class CNameRecord(override val name: String, override val ttlSeconds: Int,
                             canonicalName: String) extends ResourceRecord(name, ttlSeconds, RecordType.CNAME.code, RecordClass.IN.code) {
  /**
   * INTERNAL API
   */
  @InternalApi
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    it.putShort(DomainName.length(name))
    DomainName.write(it, name)
  }
}

@InternalApi
private[dns] object CNameRecord {
  /**
   * INTERNAL API
   */
  @InternalApi
  def parseBody(name: String, ttlSeconds: Int, length: Short, it: ByteIterator, msg: ByteString): CNameRecord = {
    CNameRecord(name, ttlSeconds, DomainName.parse(it, msg))
  }
}

@ApiMayChange
final case class SRVRecord(override val name: String, override val ttlSeconds: Int,
                           priority: Int, weight: Int, port: Int, target: String) extends ResourceRecord(name, ttlSeconds, RecordType.SRV.code, RecordClass.IN.code) {
  /**
   * INTERNAL API
   */
  @InternalApi
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    it.putShort(priority)
    it.putShort(weight)
    it.putShort(port)
    DomainName.write(it, target)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[dns] object SRVRecord {
  /**
   * INTERNAL API
   */
  @InternalApi
  def parseBody(name: String, ttlSeconds: Int, length: Short, it: ByteIterator, msg: ByteString): SRVRecord = {
    val priority = it.getShort
    val weight = it.getShort
    val port = it.getShort
    SRVRecord(name, ttlSeconds, priority, weight, port, DomainName.parse(it, msg))
  }
}

@ApiMayChange
final case class UnknownRecord(override val name: String, override val ttlSeconds: Int,
                               override val recType: Short, override val recClass: Short,
                               data: ByteString) extends ResourceRecord(name, ttlSeconds, recType, recClass) {
  /**
   * INTERNAL API
   */
  @InternalApi
  override def write(it: ByteStringBuilder): Unit = {
    super.write(it)
    it.putShort(data.length)
    it.append(data)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[dns] object UnknownRecord {
  /**
   * INTERNAL API
   */
  @InternalApi
  def parseBody(name: String, ttlSeconds: Int, recType: Short, recClass: Short, length: Short, it: ByteIterator): UnknownRecord =
    UnknownRecord(name, ttlSeconds, recType, recClass, it.toByteString)
}

/**
 * INTERNAL API
 */
@InternalApi
private[dns] object ResourceRecord {
  /**
   * INTERNAL API
   */
  @InternalApi
  def parse(it: ByteIterator, msg: ByteString): ResourceRecord = {
    val name = DomainName.parse(it, msg)
    val recType = it.getShort
    val recClass = it.getShort
    val ttl = it.getInt
    val rdLength = it.getShort
    val data = it.clone().take(rdLength)
    it.drop(rdLength)
    (recType: @switch) match {
      case 1  ⇒ ARecord.parseBody(name, ttl, rdLength, data)
      case 5  ⇒ CNameRecord.parseBody(name, ttl, rdLength, data, msg)
      case 28 ⇒ AAAARecord.parseBody(name, ttl, rdLength, data)
      case 33 ⇒ SRVRecord.parseBody(name, ttl, rdLength, data, msg)
      case _  ⇒ UnknownRecord.parseBody(name, ttl, recType, recClass, rdLength, data)
    }
  }
}

