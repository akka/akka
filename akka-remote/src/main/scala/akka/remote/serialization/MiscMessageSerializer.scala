/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.serialization

import akka.actor._
import akka.protobuf.ByteString
import akka.remote.ContainerFormats
import akka.serialization.{ Serialization, BaseSerializer, SerializationExtension, SerializerWithStringManifest }

class MiscMessageSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  private lazy val serialization = SerializationExtension(system)

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case identify: Identify      ⇒ serializeIdentify(identify)
    case identity: ActorIdentity ⇒ serializeActorIdentity(identity)
    case _                       ⇒ throw new IllegalArgumentException(s"Cannot serialize object of type [${obj.getClass.getName}]")
  }

  private def serializeIdentify(identify: Identify): Array[Byte] =
    ContainerFormats.Identify.newBuilder()
      .setMessageId(payloadBuilder(identify.messageId))
      .build()
      .toByteArray

  private def serializeActorIdentity(actorIdentity: ActorIdentity): Array[Byte] = {
    val builder =
      ContainerFormats.ActorIdentity.newBuilder()
        .setCorrelationId(payloadBuilder(actorIdentity.correlationId))

    actorIdentity.ref.foreach { actorRef ⇒
      builder.setRef(actorRefBuilder(actorRef))
    }

    builder
      .build()
      .toByteArray
  }

  private def actorRefBuilder(actorRef: ActorRef): ContainerFormats.ActorRef.Builder =
    ContainerFormats.ActorRef.newBuilder()
      .setPath(Serialization.serializedActorPath(actorRef))

  private def payloadBuilder(input: Any): ContainerFormats.Payload.Builder = {
    val payload = input.asInstanceOf[AnyRef]
    val builder = ContainerFormats.Payload.newBuilder()
    val serializer = serialization.findSerializerFor(payload)

    builder
      .setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(payload)))
      .setSerializerId(serializer.identifier)

    serializer match {
      case ser2: SerializerWithStringManifest ⇒
        val manifest = ser2.manifest(payload)
        if (manifest != "")
          builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
      case _ ⇒
        if (serializer.includeManifest)
          builder.setMessageManifest(ByteString.copyFromUtf8(payload.getClass.getName))
    }

    builder
  }

  private val IdentifyManifest = "A"
  private val ActorIdentifyManifest = "B"

  private val fromBinaryMap = Map[String, Array[Byte] ⇒ AnyRef](
    IdentifyManifest → deserializeIdentify,
    ActorIdentifyManifest → deserializeActorIdentity)

  override def manifest(o: AnyRef): String =
    o match {
      case _: Identify      ⇒ IdentifyManifest
      case _: ActorIdentity ⇒ ActorIdentifyManifest
      case _ ⇒
        throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(deserializer) ⇒ deserializer(bytes)
      case None ⇒ throw new IllegalArgumentException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
    }

  private def deserializeIdentify(bytes: Array[Byte]): Identify = {
    val identifyProto = ContainerFormats.Identify.parseFrom(bytes)
    val messageId = deserializePayload(identifyProto.getMessageId)
    Identify(messageId)
  }

  private def deserializeActorIdentity(bytes: Array[Byte]): ActorIdentity = {
    val actorIdentityProto = ContainerFormats.ActorIdentity.parseFrom(bytes)
    val correlationId = deserializePayload(actorIdentityProto.getCorrelationId)
    val actorRef =
      if (actorIdentityProto.hasRef)
        Some(deserializeActorRef(actorIdentityProto.getRef))
      else
        None
    ActorIdentity(correlationId, actorRef)
  }

  private def deserializeActorRef(actorRef: ContainerFormats.ActorRef): ActorRef =
    serialization.system.provider.resolveActorRef(actorRef.getPath)

  private def deserializePayload(payload: ContainerFormats.Payload): Any = {
    val manifest = if (payload.hasMessageManifest) payload.getMessageManifest.toStringUtf8 else ""
    serialization.deserialize(
      payload.getEnclosedMessage.toByteArray,
      payload.getSerializerId,
      manifest).get
  }

}
