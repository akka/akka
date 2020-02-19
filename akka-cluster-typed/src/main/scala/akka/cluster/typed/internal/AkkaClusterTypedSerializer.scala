/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal

import java.io.NotSerializableException

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.internal.pubsub.TopicImpl
import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.typed.internal.protobuf.ClusterMessages
import akka.cluster.typed.internal.receptionist.ClusterReceptionist.Entry
import akka.serialization.SerializationExtension
import akka.serialization.Serializers
import akka.protobufv3.internal.ByteString

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class AkkaClusterTypedSerializer(override val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  // Serializers are initialized early on. `toTyped` might then try to initialize the classic ActorSystemAdapter extension.
  private lazy val resolver = ActorRefResolver(system.toTyped)
  private lazy val serialization = SerializationExtension(system)

  private val ReceptionistEntryManifest = "a"
  private val PubSubPublishManifest = "b"

  override def manifest(o: AnyRef): String = o match {
    case _: Entry                         => ReceptionistEntryManifest
    case _: TopicImpl.MessagePublished[_] => PubSubPublishManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: Entry                         => receptionistEntryToBinary(e)
    case m: TopicImpl.MessagePublished[_] => pubSubPublishToBinary(m)
    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case ReceptionistEntryManifest => receptionistEntryFromBinary(bytes)
    case PubSubPublishManifest     => pubSubMessageFromBinary(bytes)
    case _ =>
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

  private def pubSubPublishToBinary(m: TopicImpl.MessagePublished[_]): Array[Byte] = {
    val userMessage = m.message.asInstanceOf[AnyRef]
    val serializer = serialization.serializerFor(userMessage.getClass)
    val payload = serializer.toBinary(userMessage)
    val manifest = Serializers.manifestFor(serializer, userMessage)

    ClusterMessages.PubSubMessagePublished
      .newBuilder()
      .setSerializerId(serializer.identifier)
      .setMessageManifest(manifest)
      .setMessage(ByteString.copyFrom(payload))
      .build()
      .toByteArray
  }

  private def receptionistEntryToBinary(e: Entry): Array[Byte] =
    ClusterMessages.ReceptionistEntry
      .newBuilder()
      .setActorRef(resolver.toSerializationFormat(e.ref))
      .setSystemUid(e.systemUid)
      .build()
      .toByteArray

  private def pubSubMessageFromBinary(bytes: Array[Byte]): TopicImpl.MessagePublished[_] = {
    val parsed = ClusterMessages.PubSubMessagePublished.parseFrom(bytes)
    val userMessage =
      serialization.deserialize(parsed.getMessage.toByteArray, parsed.getSerializerId, parsed.getMessageManifest).get
    TopicImpl.MessagePublished(userMessage)
  }

  private def receptionistEntryFromBinary(bytes: Array[Byte]): Entry = {
    val re = ClusterMessages.ReceptionistEntry.parseFrom(bytes)
    Entry(resolver.resolveActorRef(re.getActorRef), re.getSystemUid)
  }
}
