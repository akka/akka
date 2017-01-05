/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata.protobuf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import scala.annotation.tailrec
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.cluster.UniqueAddress
import akka.cluster.ddata.protobuf.msg.{ ReplicatorMessages ⇒ dm }
import akka.serialization.JSerializer
import akka.serialization.Serialization
import akka.serialization.SerializationExtension
import akka.protobuf.ByteString
import akka.protobuf.MessageLite
import akka.serialization.SerializerWithStringManifest

/**
 * Some useful serialization helper methods.
 */
trait SerializationSupport {

  private final val BufferSize = 1024 * 4

  def system: ExtendedActorSystem

  @volatile
  private var ser: Serialization = _
  def serialization: Serialization = {
    if (ser == null) ser = SerializationExtension(system)
    ser
  }

  @volatile
  private var protocol: String = _
  def addressProtocol: String = {
    if (protocol == null) protocol = system.provider.getDefaultAddress.protocol
    protocol
  }

  @volatile
  private var transportInfo: Serialization.Information = _
  def transportInformation: Serialization.Information = {
    if (transportInfo == null) {
      val address = system.provider.getDefaultAddress
      transportInfo = Serialization.Information(address, system)
    }
    transportInfo
  }

  def compress(msg: MessageLite): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    try msg.writeTo(zip)
    finally zip.close()
    bos.toByteArray
  }

  def decompress(bytes: Array[Byte]): Array[Byte] = {
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    val out = new ByteArrayOutputStream()
    val buffer = new Array[Byte](BufferSize)

    @tailrec def readChunk(): Unit = in.read(buffer) match {
      case -1 ⇒ ()
      case n ⇒
        out.write(buffer, 0, n)
        readChunk()
    }

    try readChunk()
    finally in.close()
    out.toByteArray
  }

  def addressToProto(address: Address): dm.Address.Builder = address match {
    case Address(_, _, Some(host), Some(port)) ⇒
      dm.Address.newBuilder().setHostname(host).setPort(port)
    case _ ⇒ throw new IllegalArgumentException(s"Address [${address}] could not be serialized: host or port missing.")
  }

  def addressFromProto(address: dm.Address): Address =
    Address(addressProtocol, system.name, address.getHostname, address.getPort)

  def uniqueAddressToProto(uniqueAddress: UniqueAddress): dm.UniqueAddress.Builder =
    dm.UniqueAddress.newBuilder().setAddress(addressToProto(uniqueAddress.address))
      .setUid(uniqueAddress.longUid.toInt)
      .setUid2((uniqueAddress.longUid >> 32).toInt)

  def uniqueAddressFromProto(uniqueAddress: dm.UniqueAddress): UniqueAddress =
    UniqueAddress(
      addressFromProto(uniqueAddress.getAddress),
      if (uniqueAddress.hasUid2) {
        // new remote node join the two parts of the long uid back
        (uniqueAddress.getUid2.toLong << 32) | (uniqueAddress.getUid & 0xFFFFFFFFL)
      } else {
        // old remote node
        uniqueAddress.getUid.toLong
      }
    )

  def resolveActorRef(path: String): ActorRef =
    system.provider.resolveActorRef(path)

  def otherMessageToProto(msg: Any): dm.OtherMessage = {
    def buildOther(): dm.OtherMessage = {
      val m = msg.asInstanceOf[AnyRef]
      val msgSerializer = serialization.findSerializerFor(m)
      val builder = dm.OtherMessage.newBuilder().
        setEnclosedMessage(ByteString.copyFrom(msgSerializer.toBinary(m)))
        .setSerializerId(msgSerializer.identifier)

      msgSerializer match {
        case ser2: SerializerWithStringManifest ⇒
          val manifest = ser2.manifest(m)
          if (manifest != "")
            builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
        case _ ⇒
          if (msgSerializer.includeManifest)
            builder.setMessageManifest(ByteString.copyFromUtf8(m.getClass.getName))
      }

      builder.build()
    }

    // Serialize actor references with full address information (defaultAddress).
    // When sending remote messages currentTransportInformation is already set,
    // but when serializing for digests it must be set here.
    if (Serialization.currentTransportInformation.value == null)
      Serialization.currentTransportInformation.withValue(transportInformation) { buildOther() }
    else
      buildOther()
  }

  def otherMessageFromBinary(bytes: Array[Byte]): AnyRef =
    otherMessageFromProto(dm.OtherMessage.parseFrom(bytes))

  def otherMessageFromProto(other: dm.OtherMessage): AnyRef = {
    val manifest = if (other.hasMessageManifest) other.getMessageManifest.toStringUtf8 else ""
    serialization.deserialize(
      other.getEnclosedMessage.toByteArray,
      other.getSerializerId,
      manifest).get
  }

}

/**
 * Java API
 */
abstract class AbstractSerializationSupport extends JSerializer with SerializationSupport
