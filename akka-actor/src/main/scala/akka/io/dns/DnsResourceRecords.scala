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
sealed abstract class ResourceRecord(val name: String, val ttlInSeconds: Int, val recType: Short, val recClass: Short)
  extends NoSerializationVerificationNeeded {
}

@ApiMayChange
final case class ARecord(override val name: String, override val ttlInSeconds: Int,
                         ip: InetAddress) extends ResourceRecord(name, ttlInSeconds, RecordType.A.code, RecordClass.IN.code) {
}

/**
 * INTERNAL API
 */
@InternalApi
private[dns] object ARecord {
  def parseBody(name: String, ttlInSeconds: Int, length: Short, it: ByteIterator): ARecord = {
    val addr = Array.ofDim[Byte](4)
    it.getBytes(addr)
    ARecord(name, ttlInSeconds, InetAddress.getByAddress(addr).asInstanceOf[Inet4Address])
  }
}

@ApiMayChange
final case class AAAARecord(override val name: String, override val ttlInSeconds: Int,
                            ip: Inet6Address) extends ResourceRecord(name, ttlInSeconds, RecordType.AAAA.code, RecordClass.IN.code) {
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
  def parseBody(name: String, ttlInSeconds: Int, length: Short, it: ByteIterator): AAAARecord = {
    val addr = Array.ofDim[Byte](16)
    it.getBytes(addr)
    AAAARecord(name, ttlInSeconds, InetAddress.getByAddress(addr).asInstanceOf[Inet6Address])
  }
}

@ApiMayChange
final case class CNameRecord(override val name: String, override val ttlInSeconds: Int,
                             canonicalName: String) extends ResourceRecord(name, ttlInSeconds, RecordType.CNAME.code, RecordClass.IN.code) {
}

@InternalApi
private[dns] object CNameRecord {
  /**
   * INTERNAL API
   */
  @InternalApi
  def parseBody(name: String, ttlInSeconds: Int, length: Short, it: ByteIterator, msg: ByteString): CNameRecord = {
    CNameRecord(name, ttlInSeconds, DomainName.parse(it, msg))
  }
}

@ApiMayChange
final case class SRVRecord(override val name: String, override val ttlInSeconds: Int,
                           priority: Int, weight: Int, port: Int, target: String) extends ResourceRecord(name, ttlInSeconds, RecordType.SRV.code, RecordClass.IN.code) {
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
  def parseBody(name: String, ttlInSeconds: Int, length: Short, it: ByteIterator, msg: ByteString): SRVRecord = {
    val priority = it.getShort.toInt & 0xFFFF
    val weight = it.getShort.toInt & 0xFFFF
    val port = it.getShort.toInt & 0xFFFF
    SRVRecord(name, ttlInSeconds, priority, weight, port, DomainName.parse(it, msg))
  }
}

@ApiMayChange
final case class UnknownRecord(override val name: String, override val ttlInSeconds: Int,
                               override val recType: Short, override val recClass: Short,
                               data: ByteString) extends ResourceRecord(name, ttlInSeconds, recType, recClass) {
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
  def parseBody(name: String, ttlInSeconds: Int, recType: Short, recClass: Short, length: Short, it: ByteIterator): UnknownRecord =
    UnknownRecord(name, ttlInSeconds, recType, recClass, it.toByteString)
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

