/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.serialization

import scala.language.existentials

import com.google.protobuf._

import akka.actor.{ ActorPath, ExtendedActorSystem }
import akka.japi.Util.immutableSeq
import akka.persistence._
import akka.persistence.serialization.MessageFormats._
import akka.serialization._

/**
 * Marker trait for all protobuf-serializable messages in `akka.persistence`.
 */
trait Message extends Serializable

/**
 * Protobuf serializer for [[PersistentBatch]], [[PersistentRepr]] and [[Deliver]] messages.
 */
class MessageSerializer(val system: ExtendedActorSystem) extends Serializer {
  import PersistentRepr.Undefined

  val PersistentBatchClass = classOf[PersistentBatch]
  val PersistentReprClass = classOf[PersistentRepr]
  val PersistentImplClass = classOf[PersistentImpl]
  val ConfirmablePersistentImplClass = classOf[ConfirmablePersistentImpl]
  val DeliveredByTransientChannelClass = classOf[DeliveredByChannel]
  val DeliveredByPersistentChannelClass = classOf[DeliveredByPersistentChannel]
  val DeliverClass = classOf[Deliver]

  def identifier: Int = 7
  def includeManifest: Boolean = true

  /**
   * Serializes [[PersistentBatch]], [[PersistentRepr]] and [[Deliver]] messages. Delegates
   * serialization of a persistent message's payload to a matching `akka.serialization.Serializer`.
   */
  def toBinary(o: AnyRef): Array[Byte] = o match {
    case b: PersistentBatch              ⇒ persistentMessageBatchBuilder(b).build().toByteArray
    case p: PersistentRepr               ⇒ persistentMessageBuilder(p).build().toByteArray
    case c: DeliveredByChannel           ⇒ deliveredMessageBuilder(c).build().toByteArray
    case c: DeliveredByPersistentChannel ⇒ deliveredMessageBuilder(c).build().toByteArray
    case d: Deliver                      ⇒ deliverMessageBuilder(d).build.toByteArray
    case _                               ⇒ throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  /**
   * Deserializes [[PersistentBatch]], [[PersistentRepr]] and [[Deliver]] messages. Delegates
   * deserialization of a persistent message's payload to a matching `akka.serialization.Serializer`.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): Message = manifest match {
    case None ⇒ persistent(PersistentMessage.parseFrom(bytes))
    case Some(c) ⇒ c match {
      case PersistentImplClass               ⇒ persistent(PersistentMessage.parseFrom(bytes))
      case ConfirmablePersistentImplClass    ⇒ persistent(PersistentMessage.parseFrom(bytes))
      case PersistentReprClass               ⇒ persistent(PersistentMessage.parseFrom(bytes))
      case PersistentBatchClass              ⇒ persistentBatch(PersistentMessageBatch.parseFrom(bytes))
      case DeliveredByTransientChannelClass  ⇒ delivered(DeliveredMessage.parseFrom(bytes))
      case DeliveredByPersistentChannelClass ⇒ delivered(DeliveredMessage.parseFrom(bytes))
      case DeliverClass                      ⇒ deliver(DeliverMessage.parseFrom(bytes))
      case _                                 ⇒ throw new IllegalArgumentException(s"Can't deserialize object of type ${c}")
    }
  }

  //
  // toBinary helpers
  //

  private def deliverMessageBuilder(deliver: Deliver) = {
    val builder = DeliverMessage.newBuilder
    builder.setPersistent(persistentMessageBuilder(deliver.persistent.asInstanceOf[PersistentRepr]))
    builder.setDestination(deliver.destination.toString)
    builder
  }

  private def persistentMessageBatchBuilder(persistentBatch: PersistentBatch) = {
    val builder = PersistentMessageBatch.newBuilder
    persistentBatch.persistentReprList.foreach(p ⇒ builder.addBatch(persistentMessageBuilder(p)))
    builder
  }

  private def persistentMessageBuilder(persistent: PersistentRepr) = {
    val builder = PersistentMessage.newBuilder

    if (persistent.processorId != Undefined) builder.setProcessorId(persistent.processorId)
    if (persistent.confirmMessage != null) builder.setConfirmMessage(deliveredMessageBuilder(persistent.confirmMessage))
    if (persistent.confirmTarget != null) builder.setConfirmTarget(Serialization.serializedActorPath(persistent.confirmTarget))
    if (persistent.sender != null) builder.setSender(Serialization.serializedActorPath(persistent.sender))

    persistent.confirms.foreach(builder.addConfirms)

    builder.setPayload(persistentPayloadBuilder(persistent.payload.asInstanceOf[AnyRef]))
    builder.setSequenceNr(persistent.sequenceNr)
    builder.setDeleted(persistent.deleted)
    builder.setRedeliveries(persistent.redeliveries)
    builder.setConfirmable(persistent.confirmable)
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

  private def deliveredMessageBuilder(delivered: Delivered) = {
    val builder = DeliveredMessage.newBuilder

    if (delivered.channel != null) builder.setChannel(Serialization.serializedActorPath(delivered.channel))

    builder.setChannelId(delivered.channelId)
    builder.setPersistentSequenceNr(delivered.persistentSequenceNr)
    builder.setDeliverySequenceNr(delivered.deliverySequenceNr)

    delivered match {
      case c: DeliveredByChannel ⇒ builder.setProcessorId(c.processorId)
      case _                     ⇒ builder
    }
  }

  //
  // fromBinary helpers
  //

  private def deliver(deliverMessage: DeliverMessage): Deliver = {
    Deliver(
      persistent(deliverMessage.getPersistent),
      ActorPath.fromString(deliverMessage.getDestination))
  }

  private def persistentBatch(persistentMessageBatch: PersistentMessageBatch): PersistentBatch =
    PersistentBatch(immutableSeq(persistentMessageBatch.getBatchList).map(persistent))

  private def persistent(persistentMessage: PersistentMessage): PersistentRepr = {
    PersistentRepr(
      payload(persistentMessage.getPayload),
      persistentMessage.getSequenceNr,
      if (persistentMessage.hasProcessorId) persistentMessage.getProcessorId else Undefined,
      persistentMessage.getDeleted,
      persistentMessage.getRedeliveries,
      immutableSeq(persistentMessage.getConfirmsList),
      persistentMessage.getConfirmable,
      if (persistentMessage.hasConfirmMessage) delivered(persistentMessage.getConfirmMessage) else null,
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

  private def delivered(deliveredMessage: DeliveredMessage): Delivered = {
    val channel = if (deliveredMessage.hasChannel) system.provider.resolveActorRef(deliveredMessage.getChannel) else null

    if (deliveredMessage.hasProcessorId) {
      DeliveredByChannel(
        deliveredMessage.getProcessorId,
        deliveredMessage.getChannelId,
        deliveredMessage.getPersistentSequenceNr,
        deliveredMessage.getDeliverySequenceNr,
        channel)
    } else {
      DeliveredByPersistentChannel(
        deliveredMessage.getChannelId,
        deliveredMessage.getPersistentSequenceNr,
        deliveredMessage.getDeliverySequenceNr,
        channel)
    }
  }
}
