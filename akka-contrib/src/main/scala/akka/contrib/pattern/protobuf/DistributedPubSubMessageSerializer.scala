/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern.protobuf

import akka.serialization.Serializer
import akka.cluster._
import scala.collection.breakOut
import akka.actor.{ ExtendedActorSystem, Address }
import scala.Some
import scala.collection.immutable
import java.io.{ ByteArrayInputStream, ObjectOutputStream, ByteArrayOutputStream }
import com.google.protobuf.ByteString
import akka.util.ClassLoaderObjectInputStream
import java.{ lang ⇒ jl }
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import com.google.protobuf.MessageLite
import scala.annotation.tailrec
import akka.contrib.pattern.protobuf.msg.{ DistributedPubSubMessages ⇒ dm }
import scala.collection.JavaConverters._
import scala.concurrent.duration.Deadline
import akka.contrib.pattern.DistributedPubSubMessage
import akka.contrib.pattern.DistributedPubSubMediator._
import akka.contrib.pattern.DistributedPubSubMediator.Internal._
import akka.serialization.Serialization
import akka.actor.ActorRef
import akka.serialization.SerializationExtension
import scala.collection.immutable.TreeMap

/**
 * Protobuf serializer of DistributedPubSubMediator messages.
 */
class DistributedPubSubMessageSerializer(val system: ExtendedActorSystem) extends Serializer {

  private final val BufferSize = 1024 * 4

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: DistributedPubSubMessage], Array[Byte] ⇒ AnyRef](
    classOf[Status] -> statusFromBinary,
    classOf[Delta] -> deltaFromBinary,
    classOf[Send] -> sendFromBinary,
    classOf[SendToAll] -> sendToAllFromBinary,
    classOf[Publish] -> publishFromBinary)

  def includeManifest: Boolean = true

  def identifier = 9

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: Status    ⇒ compress(statusToProto(m))
    case m: Delta     ⇒ compress(deltaToProto(m))
    case m: Send      ⇒ sendToProto(m).toByteArray
    case m: SendToAll ⇒ sendToAllToProto(m).toByteArray
    case m: Publish   ⇒ publishToProto(m).toByteArray
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = clazz match {
    case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[DistributedPubSubMessage]]) match {
      case Some(f) ⇒ f(bytes)
      case None    ⇒ throw new IllegalArgumentException(s"Unimplemented deserialization of message class $c in DistributedPubSubMessageSerializer")
    }
    case _ ⇒ throw new IllegalArgumentException("Need a message class to be able to deserialize bytes in DistributedPubSubMessageSerializer")
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

  private def addressToProto(address: Address): dm.Address.Builder = address match {
    case Address(protocol, system, Some(host), Some(port)) ⇒
      dm.Address.newBuilder().setSystem(system).setHostname(host).setPort(port).setProtocol(protocol)
    case _ ⇒ throw new IllegalArgumentException(s"Address [${address}] could not be serialized: host or port missing.")
  }

  private def addressFromProto(address: dm.Address): Address =
    Address(address.getProtocol, address.getSystem, address.getHostname, address.getPort)

  private def statusToProto(status: Status): dm.Status = {
    val versions = status.versions.map {
      case (a, v) ⇒
        dm.Status.Version.newBuilder().
          setAddress(addressToProto(a)).
          setTimestamp(v).
          build()
    }.toVector.asJava
    dm.Status.newBuilder().addAllVersions(versions).build()
  }

  private def statusFromBinary(bytes: Array[Byte]): Status =
    statusFromProto(dm.Status.parseFrom(decompress(bytes)))

  private def statusFromProto(status: dm.Status): Status =
    Status(status.getVersionsList.asScala.map(v ⇒
      addressFromProto(v.getAddress) -> v.getTimestamp)(breakOut))

  private def deltaToProto(delta: Delta): dm.Delta = {
    val buckets = delta.buckets.map { b ⇒
      val entries = b.content.map {
        case (key, value) ⇒
          val b = dm.Delta.Entry.newBuilder().setKey(key).setVersion(value.version)
          value.ref.foreach(r ⇒ b.setRef(Serialization.serializedActorPath(r)))
          b.build()
      }.toVector.asJava

      dm.Delta.Bucket.newBuilder().
        setOwner(addressToProto(b.owner)).
        setVersion(b.version).
        addAllContent(entries).
        build()
    }.toVector.asJava
    dm.Delta.newBuilder().addAllBuckets(buckets).build()
  }

  private def deltaFromBinary(bytes: Array[Byte]): Delta =
    deltaFromProto(dm.Delta.parseFrom(decompress(bytes)))

  private def deltaFromProto(delta: dm.Delta): Delta =
    Delta(delta.getBucketsList.asScala.toVector.map { b ⇒
      val content: TreeMap[String, ValueHolder] = b.getContentList.asScala.map { entry ⇒
        entry.getKey -> ValueHolder(entry.getVersion, if (entry.hasRef) Some(resolveActorRef(entry.getRef)) else None)
      }(breakOut)
      Bucket(addressFromProto(b.getOwner), b.getVersion, content)
    })

  private def resolveActorRef(path: String): ActorRef = {
    system.provider.resolveActorRef(path)
  }

  private def sendToProto(send: Send): dm.Send = {
    dm.Send.newBuilder().
      setPath(send.path).
      setLocalAffinity(send.localAffinity).
      setPayload(payloadToProto(send.msg)).
      build()
  }

  private def sendFromBinary(bytes: Array[Byte]): Send =
    sendFromProto(dm.Send.parseFrom(bytes))

  private def sendFromProto(send: dm.Send): Send =
    Send(send.getPath, payloadFromProto(send.getPayload), send.getLocalAffinity)

  private def sendToAllToProto(sendToAll: SendToAll): dm.SendToAll = {
    dm.SendToAll.newBuilder().
      setPath(sendToAll.path).
      setAllButSelf(sendToAll.allButSelf).
      setPayload(payloadToProto(sendToAll.msg)).
      build()
  }

  private def sendToAllFromBinary(bytes: Array[Byte]): SendToAll =
    sendToAllFromProto(dm.SendToAll.parseFrom(bytes))

  private def sendToAllFromProto(sendToAll: dm.SendToAll): SendToAll =
    SendToAll(sendToAll.getPath, payloadFromProto(sendToAll.getPayload), sendToAll.getAllButSelf)

  private def publishToProto(publish: Publish): dm.Publish = {
    dm.Publish.newBuilder().
      setTopic(publish.topic).
      setPayload(payloadToProto(publish.msg)).
      build()
  }

  private def publishFromBinary(bytes: Array[Byte]): Publish =
    publishFromProto(dm.Publish.parseFrom(bytes))

  private def publishFromProto(publish: dm.Publish): Publish =
    Publish(publish.getTopic, payloadFromProto(publish.getPayload))

  private def payloadToProto(msg: Any): dm.Payload = {
    val m = msg.asInstanceOf[AnyRef]
    val msgSerializer = SerializationExtension(system).findSerializerFor(m)
    val builder = dm.Payload.newBuilder().
      setEnclosedMessage(ByteString.copyFrom(msgSerializer.toBinary(m)))
      .setSerializerId(msgSerializer.identifier)
    if (msgSerializer.includeManifest)
      builder.setMessageManifest(ByteString.copyFromUtf8(m.getClass.getName))
    builder.build()
  }

  private def payloadFromProto(payload: dm.Payload): AnyRef = {
    SerializationExtension(system).deserialize(
      payload.getEnclosedMessage.toByteArray,
      payload.getSerializerId,
      if (payload.hasMessageManifest)
        Some(system.dynamicAccess.getClassFor[AnyRef](payload.getMessageManifest.toStringUtf8).get) else None).get
  }

}
