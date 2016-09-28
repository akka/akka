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
  private val payloadSupport = new WrappedPayloadSupport(system)
  private val throwableSupport = new ThrowableSupport(system)

  private val NoneSerialized = Array.empty[Byte]

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case identify: Identify      ⇒ serializeIdentify(identify)
    case identity: ActorIdentity ⇒ serializeActorIdentity(identity)
    case Some(value)             ⇒ serializeSome(value)
    case None                    ⇒ NoneSerialized
    case r: ActorRef             ⇒ serializeActorRef(r)
    case s: Status.Success       ⇒ serializeStatusSuccess(s)
    case f: Status.Failure       ⇒ serializeStatusFailure(f)
    case t: Throwable            ⇒ throwableSupport.serializeThrowable(t)
    case _                       ⇒ throw new IllegalArgumentException(s"Cannot serialize object of type [${obj.getClass.getName}]")
  }

  private def serializeIdentify(identify: Identify): Array[Byte] =
    ContainerFormats.Identify.newBuilder()
      .setMessageId(payloadSupport.payloadBuilder(identify.messageId))
      .build()
      .toByteArray

  private def serializeActorIdentity(actorIdentity: ActorIdentity): Array[Byte] = {
    val builder =
      ContainerFormats.ActorIdentity.newBuilder()
        .setCorrelationId(payloadSupport.payloadBuilder(actorIdentity.correlationId))

    actorIdentity.ref.foreach { actorRef ⇒
      builder.setRef(actorRefBuilder(actorRef))
    }

    builder
      .build()
      .toByteArray
  }

  private def serializeSome(someValue: Any): Array[Byte] =
    ContainerFormats.Option.newBuilder()
      .setValue(payloadSupport.payloadBuilder(someValue))
      .build()
      .toByteArray

  private def serializeActorRef(ref: ActorRef): Array[Byte] =
    actorRefBuilder(ref).build().toByteArray

  private def actorRefBuilder(actorRef: ActorRef): ContainerFormats.ActorRef.Builder =
    ContainerFormats.ActorRef.newBuilder()
      .setPath(Serialization.serializedActorPath(actorRef))

  private def serializeStatusSuccess(success: Status.Success): Array[Byte] =
    payloadSupport.payloadBuilder(success.status).build().toByteArray

  private def serializeStatusFailure(failure: Status.Failure): Array[Byte] =
    payloadSupport.payloadBuilder(failure.cause).build().toByteArray

  private val IdentifyManifest = "A"
  private val ActorIdentifyManifest = "B"
  private val OptionManifest = "C"
  private val StatusSuccessManifest = "D"
  private val StatusFailureManifest = "E"
  private val ThrowableManifest = "F"
  private val ActorRefManifest = "G"

  private val fromBinaryMap = Map[String, Array[Byte] ⇒ AnyRef](
    IdentifyManifest → deserializeIdentify,
    ActorIdentifyManifest → deserializeActorIdentity,
    OptionManifest → deserializeOption,
    StatusSuccessManifest → deserializeStatusSuccess,
    StatusFailureManifest → deserializeStatusFailure,
    ThrowableManifest → throwableSupport.deserializeThrowable,
    ActorRefManifest → deserializeActorRefBytes)

  override def manifest(o: AnyRef): String =
    o match {
      case _: Identify       ⇒ IdentifyManifest
      case _: ActorIdentity  ⇒ ActorIdentifyManifest
      case _: Option[Any]    ⇒ OptionManifest
      case _: ActorRef       ⇒ ActorRefManifest
      case _: Status.Success ⇒ StatusSuccessManifest
      case _: Status.Failure ⇒ StatusFailureManifest
      case _: Throwable      ⇒ ThrowableManifest
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
    val messageId = payloadSupport.deserializePayload(identifyProto.getMessageId)
    Identify(messageId)
  }

  private def deserializeActorIdentity(bytes: Array[Byte]): ActorIdentity = {
    val actorIdentityProto = ContainerFormats.ActorIdentity.parseFrom(bytes)
    val correlationId = payloadSupport.deserializePayload(actorIdentityProto.getCorrelationId)
    val actorRef =
      if (actorIdentityProto.hasRef)
        Some(deserializeActorRef(actorIdentityProto.getRef))
      else
        None
    ActorIdentity(correlationId, actorRef)
  }

  private def deserializeActorRefBytes(bytes: Array[Byte]): ActorRef =
    deserializeActorRef(ContainerFormats.ActorRef.parseFrom(bytes))

  private def deserializeActorRef(actorRef: ContainerFormats.ActorRef): ActorRef =
    serialization.system.provider.resolveActorRef(actorRef.getPath)

  private def deserializeOption(bytes: Array[Byte]): Option[Any] = {
    if (bytes.length == 0)
      None
    else {
      val optionProto = ContainerFormats.Option.parseFrom(bytes)
      Some(payloadSupport.deserializePayload(optionProto.getValue))
    }
  }

  private def deserializeStatusSuccess(bytes: Array[Byte]): Status.Success =
    Status.Success(payloadSupport.deserializePayload(ContainerFormats.Payload.parseFrom(bytes)))

  private def deserializeStatusFailure(bytes: Array[Byte]): Status.Failure =
    Status.Failure(payloadSupport.deserializePayload(ContainerFormats.Payload.parseFrom(bytes)).asInstanceOf[Throwable])

}
