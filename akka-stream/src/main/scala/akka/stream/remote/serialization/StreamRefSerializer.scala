/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.protobuf.ByteString
import akka.serialization.{ BaseSerializer, Serialization, SerializationExtension, SerializerWithStringManifest }
import akka.stream.remote.scaladsl.{ SinkRef, SourceRef }
import akka.stream.remote.{ StreamRefContainers, StreamRefs }

final class StreamRefSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest
  with BaseSerializer {

  private[this] lazy val serialization = SerializationExtension(system)

  private[this] val SequencedOnNextManifest = "A"
  private[this] val CumulativeDemandManifest = "B"
  private[this] val RemoteSinkFailureManifest = "C"
  private[this] val RemoteSinkCompletedManifest = "D"
  private[this] val SourceRefManifest = "E"
  private[this] val SinkRefManifest = "F"

  override def manifest(o: AnyRef): String = o match {
    // protocol
    case _: StreamRefs.SequencedOnNext[_]  ⇒ SequencedOnNextManifest
    case _: StreamRefs.CumulativeDemand    ⇒ CumulativeDemandManifest
    case _: StreamRefs.RemoteSinkFailure   ⇒ RemoteSinkFailureManifest
    case _: StreamRefs.RemoteSinkCompleted ⇒ RemoteSinkCompletedManifest
    // refs
    case _: SourceRef[_]                   ⇒ SourceRefManifest
    case _: SinkRef[_]                     ⇒ SinkRefManifest
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    // protocol
    case o: StreamRefs.SequencedOnNext[_]  ⇒ serializeSequencedOnNext(o).toByteArray
    case d: StreamRefs.CumulativeDemand    ⇒ serializeCumulativeDemand(d).toByteArray
    case d: StreamRefs.RemoteSinkFailure   ⇒ serializeRemoteSinkFailure(d).toByteArray
    case d: StreamRefs.RemoteSinkCompleted ⇒ serializeRemoteSinkCompleted(d).toByteArray
    // refs
    case ref: SinkRef[_]                   ⇒ serializeSinkRef(ref).toByteArray
    case ref: SourceRef[_]                 ⇒ serializeSourceRef(ref).toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    // protocol
    case SequencedOnNextManifest     ⇒ deserializeSequencedOnNext(bytes)
    case CumulativeDemandManifest    ⇒ deserializeCumulativeDemand(bytes)
    case RemoteSinkCompletedManifest ⇒ deserializeRemoteSinkCompleted(bytes)
    case RemoteSinkFailureManifest   ⇒ deserializeRemoteSinkFailure(bytes)
    // refs
    case SinkRefManifest             ⇒ deserializeSinkRef(bytes)
    case SourceRefManifest           ⇒ deserializeSourceRef(bytes)
  }

  // -----

  private def serializeCumulativeDemand(d: StreamRefs.CumulativeDemand): StreamRefContainers.CumulativeDemand = {
    StreamRefContainers.CumulativeDemand.newBuilder()
      .setSeqNr(d.seqNr)
      .build()
  }

  private def serializeRemoteSinkFailure(d: StreamRefs.RemoteSinkFailure): StreamRefContainers.RemoteSinkFailure = {
    StreamRefContainers.RemoteSinkFailure.newBuilder()
      .setCause(ByteString.copyFrom(d.msg.getBytes))
      .build()
  }

  private def serializeRemoteSinkCompleted(d: StreamRefs.RemoteSinkCompleted): StreamRefContainers.RemoteSinkCompleted = {
    StreamRefContainers.RemoteSinkCompleted.newBuilder()
      .setSeqNr(d.seqNr)
      .build()
  }

  private def serializeSequencedOnNext(o: StreamRefs.SequencedOnNext[_]) = {
    val p = o.payload.asInstanceOf[AnyRef]
    val msgSerializer = serialization.findSerializerFor(p)

    val payloadBuilder = StreamRefContainers.Payload.newBuilder()
      .setEnclosedMessage(ByteString.copyFrom(msgSerializer.toBinary(p)))
      .setSerializerId(msgSerializer.identifier)

    msgSerializer match {
      case ser2: SerializerWithStringManifest ⇒
        val manifest = ser2.manifest(p)
        if (manifest != "")
          payloadBuilder.setMessageManifest(ByteString.copyFromUtf8(manifest))
      case _ ⇒
        if (msgSerializer.includeManifest)
          payloadBuilder.setMessageManifest(ByteString.copyFromUtf8(p.getClass.getName))
    }

    StreamRefContainers.SequencedOnNext.newBuilder()
      .setSeqNr(o.seqNr)
      .setPayload(payloadBuilder.build())
      .build()
  }

  private def serializeSinkRef(sink: SinkRef[_]): StreamRefContainers.SinkRef = {
    val actorRef = StreamRefContainers.ActorRef.newBuilder()
      .setPath(Serialization.serializedActorPath(sink.targetRef))

    StreamRefContainers.SinkRef.newBuilder()
      .setInitialDemand(sink.initialDemand)
      .setTargetRef(actorRef)
      .build()
  }

  private def serializeSourceRef(source: SourceRef[_]): StreamRefContainers.SourceRef = {
    val actorRef = StreamRefContainers.ActorRef.newBuilder()
      .setPath(Serialization.serializedActorPath(source.originRef))

    StreamRefContainers.SourceRef.newBuilder()
      .setOriginRef(actorRef)
      .build()
  }

  // ----------

  private def deserializeSinkRef(bytes: Array[Byte]): SinkRef[Any] = {
    val ref = StreamRefContainers.SinkRef.parseFrom(bytes)
    val targetRef = serialization.system.provider.resolveActorRef(ref.getTargetRef.getPath)

    new SinkRef[Any](targetRef, ref.getInitialDemand)
  }

  private def deserializeSourceRef(bytes: Array[Byte]): SourceRef[Any] = {
    val ref = StreamRefContainers.SourceRef.parseFrom(bytes)
    val targetRef = serialization.system.provider.resolveActorRef(ref.getOriginRef.getPath)

    new SourceRef[Any](targetRef)
  }

  private def deserializeSequencedOnNext(bytes: Array[Byte]): AnyRef = {
    val o = StreamRefContainers.SequencedOnNext.parseFrom(bytes)
    val p = o.getPayload
    val payload = serialization.deserialize(
      p.getEnclosedMessage.toByteArray,
      p.getSerializerId,
      p.getMessageManifest.toStringUtf8
    )
    StreamRefs.SequencedOnNext(o.getSeqNr, payload.get)
  }

  private def deserializeCumulativeDemand(bytes: Array[Byte]): StreamRefs.CumulativeDemand = {
    val d = StreamRefContainers.CumulativeDemand.parseFrom(bytes)
    StreamRefs.CumulativeDemand(d.getSeqNr)
  }
  private def deserializeRemoteSinkCompleted(bytes: Array[Byte]): StreamRefs.RemoteSinkCompleted = {
    val d = StreamRefContainers.RemoteSinkCompleted.parseFrom(bytes)
    StreamRefs.RemoteSinkCompleted(d.getSeqNr)
  }
  private def deserializeRemoteSinkFailure(bytes: Array[Byte]): AnyRef = {
    val d = StreamRefContainers.RemoteSinkFailure.parseFrom(bytes)
    StreamRefs.RemoteSinkFailure(d.getCause.toStringUtf8)
  }

}
