/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps

import java.io.NotSerializableException
import akka.annotation.InternalApi
import akka.cluster.sharding.typed.ChangeNumberOfProcesses
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.internal.protobuf.ShardingMessages
import akka.protobufv3.internal.CodedOutputStream
import akka.remote.serialization.WrappedPayloadSupport
import akka.serialization.BaseSerializer
import akka.serialization.ByteBufferSerializer
import akka.serialization.SerializerWithStringManifest

import java.nio.ByteBuffer
import java.time.Instant

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ShardingSerializer(val system: akka.actor.ExtendedActorSystem)
    extends SerializerWithStringManifest
    with ByteBufferSerializer
    with BaseSerializer {

  private val payloadSupport = new WrappedPayloadSupport(system)
  private lazy val resolver = ActorRefResolver(system.toTyped)

  private val ShardingEnvelopeManifest = "a"
  private val DaemonProcessStateManifest = "b"
  private val ChangeNumberOfProcessesManifest = "c"

  override def manifest(o: AnyRef): String = o match {
    case _: ShardingEnvelope[_]       => ShardingEnvelopeManifest
    case _: ShardedDaemonProcessState => DaemonProcessStateManifest
    case _: ChangeNumberOfProcesses   => ChangeNumberOfProcessesManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case env: ShardingEnvelope[_] =>
      val builder = ShardingMessages.ShardingEnvelope.newBuilder()
      builder.setEntityId(env.entityId)
      builder.setMessage(payloadSupport.payloadBuilder(env.message))
      builder.build().toByteArray()

    case state: ShardedDaemonProcessState =>
      ShardingMessages.DaemonProcessScaleState
        .newBuilder()
        .setRevision(state.revision)
        .setNumberOfProcesses(state.numberOfProcesses)
        .setCompleted(state.completed)
        .setStartedTimestampMillis(state.started.toEpochMilli)
        .build()
        .toByteArray()

    case change: ChangeNumberOfProcesses =>
      ShardingMessages.ChangeNumberOfProcesses
        .newBuilder()
        .setNewNumberOfProcesses(change.newNumberOfProcesses)
        .setReplyTo(resolver.toSerializationFormat(change.replyTo))
        .build()
        .toByteArray

    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case ShardingEnvelopeManifest =>
      val env = ShardingMessages.ShardingEnvelope.parseFrom(bytes)
      val entityId = env.getEntityId
      val wrappedMsg = payloadSupport.deserializePayload(env.getMessage)
      ShardingEnvelope(entityId, wrappedMsg)

    case DaemonProcessStateManifest =>
      val st = ShardingMessages.DaemonProcessScaleState.parseFrom(bytes)
      ShardedDaemonProcessState(
        st.getRevision,
        st.getNumberOfProcesses,
        st.getCompleted,
        Instant.ofEpochMilli(st.getStartedTimestampMillis))

    case ChangeNumberOfProcessesManifest =>
      val change = ShardingMessages.ChangeNumberOfProcesses.parseFrom(bytes)
      ChangeNumberOfProcesses(change.getNewNumberOfProcesses, resolver.resolveActorRef(change.getReplyTo))

    case _ =>
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

  // buffer based avoiding a copy for artery
  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit = o match {
    case env: ShardingEnvelope[_] =>
      val builder = ShardingMessages.ShardingEnvelope.newBuilder()
      builder.setEntityId(env.entityId)
      builder.setMessage(payloadSupport.payloadBuilder(env.message))
      val codedOutputStream = CodedOutputStream.newInstance(buf)
      builder.build().writeTo(codedOutputStream)
      codedOutputStream.flush()

    case state: ShardedDaemonProcessState =>
      val codedOutputStream = CodedOutputStream.newInstance(buf)
      ShardingMessages.DaemonProcessScaleState
        .newBuilder()
        .setRevision(state.revision)
        .setNumberOfProcesses(state.numberOfProcesses)
        .setCompleted(state.completed)
        .setStartedTimestampMillis(state.started.toEpochMilli)
        .build()
        .writeTo(codedOutputStream)
      codedOutputStream.flush()

    case change: ChangeNumberOfProcesses =>
      val codedOutputStream = CodedOutputStream.newInstance(buf)
      ShardingMessages.ChangeNumberOfProcesses
        .newBuilder()
        .setNewNumberOfProcesses(change.newNumberOfProcesses)
        .setReplyTo(resolver.toSerializationFormat(change.replyTo))
        .build()
        .writeTo(codedOutputStream)
      codedOutputStream.flush()

    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = manifest match {
    case ShardingEnvelopeManifest =>
      val env = ShardingMessages.ShardingEnvelope.parseFrom(buf)
      val entityId = env.getEntityId
      val wrappedMsg = payloadSupport.deserializePayload(env.getMessage)
      ShardingEnvelope(entityId, wrappedMsg)

    case DaemonProcessStateManifest =>
      val st = ShardingMessages.DaemonProcessScaleState.parseFrom(buf)
      ShardedDaemonProcessState(
        st.getRevision,
        st.getNumberOfProcesses,
        st.getCompleted,
        Instant.ofEpochMilli(st.getStartedTimestampMillis))

    case ChangeNumberOfProcessesManifest =>
      val change = ShardingMessages.ChangeNumberOfProcesses.parseFrom(buf)
      ChangeNumberOfProcesses(change.getNewNumberOfProcesses, resolver.resolveActorRef(change.getReplyTo))

    case _ =>
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

}
