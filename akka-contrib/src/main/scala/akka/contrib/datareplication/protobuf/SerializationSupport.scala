/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication.protobuf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import scala.annotation.tailrec
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.cluster.UniqueAddress
import akka.contrib.datareplication.protobuf.msg.{ ReplicatorMessages ⇒ dm }
import akka.serialization.SerializationExtension
import akka.serialization.JSerializer

/**
 * Some useful serialization helper methods.
 */
trait SerializationSupport {

  private final val BufferSize = 1024 * 4

  def system: ExtendedActorSystem

  def compress(msg: MessageLite): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    msg.writeTo(zip)
    zip.close()
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

    readChunk()
    out.toByteArray
  }

  def addressToProto(address: Address): dm.Address.Builder = address match {
    case Address(protocol, system, Some(host), Some(port)) ⇒
      dm.Address.newBuilder().setSystem(system).setHostname(host).setPort(port).setProtocol(protocol)
    case _ ⇒ throw new IllegalArgumentException(s"Address [${address}] could not be serialized: host or port missing.")
  }

  def addressFromProto(address: dm.Address): Address =
    Address(address.getProtocol, address.getSystem, address.getHostname, address.getPort)

  def uniqueAddressToProto(uniqueAddress: UniqueAddress): dm.UniqueAddress.Builder =
    dm.UniqueAddress.newBuilder().setAddress(addressToProto(uniqueAddress.address)).setUid(uniqueAddress.uid)

  def uniqueAddressFromProto(uniqueAddress: dm.UniqueAddress): UniqueAddress =
    UniqueAddress(addressFromProto(uniqueAddress.getAddress), uniqueAddress.getUid)

  def resolveActorRef(path: String): ActorRef =
    system.provider.resolveActorRef(path)

  def otherMessageToProto(msg: Any): dm.OtherMessage = {
    val m = msg.asInstanceOf[AnyRef]
    val msgSerializer = SerializationExtension(system).findSerializerFor(m)
    val builder = dm.OtherMessage.newBuilder().
      setEnclosedMessage(ByteString.copyFrom(msgSerializer.toBinary(m)))
      .setSerializerId(msgSerializer.identifier)
    if (msgSerializer.includeManifest)
      builder.setMessageManifest(ByteString.copyFromUtf8(m.getClass.getName))
    builder.build()
  }

  def otherMessageFromProto(other: dm.OtherMessage): AnyRef = {
    SerializationExtension(system).deserialize(
      other.getEnclosedMessage.toByteArray,
      other.getSerializerId,
      if (other.hasMessageManifest)
        Some(system.dynamicAccess.getClassFor[AnyRef](other.getMessageManifest.toStringUtf8).get) else None).get
  }

}

/**
 * Java API
 */
abstract class AbstractSerializationSupport extends JSerializer with SerializationSupport
