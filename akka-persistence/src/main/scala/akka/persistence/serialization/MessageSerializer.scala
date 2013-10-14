/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.serialization

import scala.language.existentials

import com.google.protobuf._

import akka.actor.ExtendedActorSystem
import akka.persistence._
import akka.persistence.serialization.MessageFormats._
import akka.serialization._

/**
 * Protobuf serializer for [[Persistent]] and `Confirm` messages.
 */
class MessageSerializer(val system: ExtendedActorSystem) extends Serializer {
  import PersistentImpl.Undefined

  val PersistentClass = classOf[PersistentImpl]
  val ConfirmClass = classOf[Confirm]

  def identifier: Int = 7
  def includeManifest: Boolean = true

  /**
   * Serializes a [[Persistent]] message. Delegates serialization of the persistent message's
   * payload to a matching `akka.serialization.Serializer`.
   */
  def toBinary(o: AnyRef): Array[Byte] = o match {
    case p: PersistentImpl ⇒ persistentMessageBuilder(p).build().toByteArray
    case c: Confirm        ⇒ confirmMessageBuilder(c).build().toByteArray
    case _                 ⇒ throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  /**
   * Deserializes a [[Persistent]] message. Delegates deserialization of the persistent message's
   * payload to a matching `akka.serialization.Serializer`.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest match {
    case None ⇒ persistent(PersistentMessage.parseFrom(bytes))
    case Some(c) ⇒ c match {
      case PersistentClass ⇒ persistent(PersistentMessage.parseFrom(bytes))
      case ConfirmClass    ⇒ confirm(ConfirmMessage.parseFrom(bytes))
      case _               ⇒ throw new IllegalArgumentException(s"Can't deserialize object of type ${c}")
    }
  }

  //
  // toBinary helpers
  //

  private def persistentMessageBuilder(persistent: PersistentImpl) = {
    val builder = PersistentMessage.newBuilder

    if (persistent.processorId != Undefined) builder.setProcessorId(persistent.processorId)
    if (persistent.channelId != Undefined) builder.setChannelId(persistent.channelId)
    if (persistent.confirmMessage != null) builder.setConfirmMessage(confirmMessageBuilder(persistent.confirmMessage))
    if (persistent.confirmTarget != null) builder.setConfirmTarget(Serialization.serializedActorPath(persistent.confirmTarget))
    if (persistent.sender != null) builder.setSender(Serialization.serializedActorPath(persistent.sender))

    persistent.confirms.foreach(builder.addConfirms)

    builder.setPayload(persistentPayloadBuilder(persistent.payload.asInstanceOf[AnyRef]))
    builder.setSequenceNr(persistent.sequenceNr)
    builder.setDeleted(persistent.deleted)
    builder.setResolved(persistent.resolved)
    builder
  }

  private def persistentPayloadBuilder(payload: AnyRef) = {
    val serializer = SerializationExtension(system).findSerializerFor(payload)
    val builder = PersistentPayload.newBuilder()

    if (serializer.includeManifest) builder.setPayloadManifest((ByteString.copyFromUtf8(payload.getClass.getName)))

    builder.setPayload(ByteString.copyFrom(serializer.toBinary(payload)))
    builder.setSerializerId(serializer.identifier)
    builder
  }

  private def confirmMessageBuilder(confirm: Confirm) = {
    ConfirmMessage.newBuilder
      .setProcessorId(confirm.processorId)
      .setSequenceNr(confirm.sequenceNr)
      .setChannelId(confirm.channelId)
  }

  //
  // fromBinary helpers
  //

  private def persistent(persistentMessage: PersistentMessage): PersistentImpl = {
    import scala.collection.JavaConverters._
    PersistentImpl(
      payload(persistentMessage.getPayload),
      persistentMessage.getSequenceNr,
      if (persistentMessage.hasProcessorId) persistentMessage.getProcessorId else Undefined,
      if (persistentMessage.hasChannelId) persistentMessage.getChannelId else Undefined,
      persistentMessage.getDeleted,
      persistentMessage.getResolved,
      persistentMessage.getConfirmsList.asScala.toList,
      if (persistentMessage.hasConfirmMessage) confirm(persistentMessage.getConfirmMessage) else null,
      if (persistentMessage.hasConfirmTarget) system.provider.resolveActorRef(persistentMessage.getConfirmTarget) else null,
      if (persistentMessage.hasSender) system.provider.resolveActorRef(persistentMessage.getSender) else null)
  }

  private def payload(persistentPayload: PersistentPayload): Any = {
    val payloadClass = if (persistentPayload.hasPayloadManifest)
      Some(system.dynamicAccess.getClassFor[AnyRef](persistentPayload.getPayloadManifest.toStringUtf8).get) else None

    SerializationExtension(system).deserialize(
      persistentPayload.getPayload.toByteArray,
      persistentPayload.getSerializerId,
      payloadClass).get
  }

  private def confirm(confirmMessage: ConfirmMessage): Confirm = {
    Confirm(
      confirmMessage.getProcessorId,
      confirmMessage.getSequenceNr,
      confirmMessage.getChannelId)
  }
}
