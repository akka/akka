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

  private val NoneSerialized = Array.empty[Byte]

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case identify: Identify      ⇒ serializeIdentify(identify)
    case identity: ActorIdentity ⇒ serializeActorIdentity(identity)
    case Some(value)             ⇒ serializeSome(value)
    case None                    ⇒ NoneSerialized
    case s: Status.Success       ⇒ serializeStatusSuccess(s)
    case f: Status.Failure       ⇒ serializeStatusFailure(f)
    case t: Throwable            ⇒ serializeThrowable(t)
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

  private def serializeSome(someValue: Any): Array[Byte] =
    ContainerFormats.Option.newBuilder()
      .setValue(payloadBuilder(someValue))
      .build()
      .toByteArray

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

  private def serializeStatusSuccess(success: Status.Success): Array[Byte] =
    payloadBuilder(success.status).build().toByteArray

  private def serializeStatusFailure(failure: Status.Failure): Array[Byte] =
    payloadBuilder(failure.cause).build().toByteArray

  private def serializeThrowable(t: Throwable): Array[Byte] = {
    val b = ContainerFormats.Throwable.newBuilder()
      .setClazzName(t.getClass.getName)
    if (t.getMessage != null)
      b.setMessage(t.getMessage)
    if (t.getCause != null)
      b.setCause(payloadBuilder(t.getCause))
    val stackTrace = t.getStackTrace
    if (stackTrace != null) {
      var i = 0
      while (i < stackTrace.length) {
        b.addStackTrace(stackTraceElementBuilder(stackTrace(i)))
        i += 1
      }
    }

    b.build().toByteArray
  }

  private def stackTraceElementBuilder(elem: StackTraceElement): ContainerFormats.StackTraceElement.Builder = {
    ContainerFormats.StackTraceElement.newBuilder()
      .setClazzName(elem.getClassName)
      .setMethodName(elem.getMethodName)
      .setFileName(elem.getFileName)
      .setLineNumber(elem.getLineNumber)
  }

  private val IdentifyManifest = "A"
  private val ActorIdentifyManifest = "B"
  private val OptionManifest = "C"
  private val StatusSuccessManifest = "D"
  private val StatusFailureManifest = "E"
  private val ThrowableManifest = "F"

  private val fromBinaryMap = Map[String, Array[Byte] ⇒ AnyRef](
    IdentifyManifest → deserializeIdentify,
    ActorIdentifyManifest → deserializeActorIdentity,
    OptionManifest → deserializeOption,
    StatusSuccessManifest → deserializeStatusSuccess,
    StatusFailureManifest → deserializeStatusFailure,
    ThrowableManifest → deserializeThrowable)

  override def manifest(o: AnyRef): String =
    o match {
      case _: Identify       ⇒ IdentifyManifest
      case _: ActorIdentity  ⇒ ActorIdentifyManifest
      case _: Option[Any]    ⇒ OptionManifest
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

  private def deserializeOption(bytes: Array[Byte]): Option[Any] = {
    if (bytes.length == 0)
      None
    else {
      val optionProto = ContainerFormats.Option.parseFrom(bytes)
      Some(deserializePayload(optionProto.getValue))
    }
  }

  private def deserializeStatusSuccess(bytes: Array[Byte]): Status.Success =
    Status.Success(deserializePayload(ContainerFormats.Payload.parseFrom(bytes)))

  private def deserializeStatusFailure(bytes: Array[Byte]): Status.Failure =
    Status.Failure(deserializePayload(ContainerFormats.Payload.parseFrom(bytes)).asInstanceOf[Throwable])

  private def deserializeThrowable(bytes: Array[Byte]): Throwable = {
    val protoT = ContainerFormats.Throwable.parseFrom(bytes)
    val t: Throwable =
      if (protoT.hasCause) {
        val cause = deserializePayload(protoT.getCause).asInstanceOf[Throwable]
        system.dynamicAccess.createInstanceFor[Throwable](
          protoT.getClazzName,
          List(classOf[String] → protoT.getMessage, classOf[Throwable] → cause)).get
      } else
        system.dynamicAccess.createInstanceFor[Throwable](
          protoT.getClazzName,
          List(classOf[String] → protoT.getMessage)).get

    import scala.collection.JavaConverters._
    val stackTrace =
      (protoT.getStackTraceList.asScala.map { elem ⇒
        new StackTraceElement(elem.getClazzName, elem.getMethodName, elem.getFileName, elem.getLineNumber)
      }).toArray
    t.setStackTrace(stackTrace)
    t
  }

  private def deserializePayload(payload: ContainerFormats.Payload): Any = {
    val manifest = if (payload.hasMessageManifest) payload.getMessageManifest.toStringUtf8 else ""
    serialization.deserialize(
      payload.getEnclosedMessage.toByteArray,
      payload.getSerializerId,
      manifest).get
  }

}
