/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.serialization

import akka.actor.{ ActorPath, ExtendedActorSystem }
import akka.persistence.AtLeastOnceDelivery._
import akka.persistence._
import akka.persistence.fsm.PersistentFSM.StateChangeEvent
import akka.persistence.serialization.{ MessageFormats ⇒ mf }
import akka.serialization._
import akka.protobuf._
import scala.collection.immutable.VectorBuilder
import scala.concurrent.duration
import akka.actor.Actor
import scala.concurrent.duration.Duration
import scala.language.existentials

/**
 * Marker trait for all protobuf-serializable messages in `akka.persistence`.
 */
trait Message extends Serializable

/**
 * Protobuf serializer for [[akka.persistence.PersistentRepr]], [[akka.persistence.AtLeastOnceDelivery]] and [[akka.persistence.fsm.PersistentFSM.StateChangeEvent]] messages.
 */
class MessageSerializer(val system: ExtendedActorSystem) extends BaseSerializer {
  import PersistentRepr.Undefined

  val AtomicWriteClass = classOf[AtomicWrite]
  val PersistentReprClass = classOf[PersistentRepr]
  val PersistentImplClass = classOf[PersistentImpl]
  val AtLeastOnceDeliverySnapshotClass = classOf[AtLeastOnceDeliverySnapshot]
  val PersistentStateChangeEventClass = classOf[StateChangeEvent]

  private lazy val serialization = SerializationExtension(system)

  override val includeManifest: Boolean = true

  private lazy val transportInformation: Option[Serialization.Information] = {
    val address = system.provider.getDefaultAddress
    if (address.hasLocalScope) None
    else Some(Serialization.Information(address, system))
  }

  /**
   * Serializes persistent messages. Delegates serialization of a persistent
   * message's payload to a matching `akka.serialization.Serializer`.
   */
  def toBinary(o: AnyRef): Array[Byte] = o match {
    case p: PersistentRepr              ⇒ persistentMessageBuilder(p).build().toByteArray
    case a: AtomicWrite                 ⇒ atomicWriteBuilder(a).build().toByteArray
    case a: AtLeastOnceDeliverySnapshot ⇒ atLeastOnceDeliverySnapshotBuilder(a).build.toByteArray
    case s: StateChangeEvent            ⇒ stateChangeBuilder(s).build.toByteArray
    case _                              ⇒ throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  /**
   * Deserializes persistent messages. Delegates deserialization of a persistent
   * message's payload to a matching `akka.serialization.Serializer`.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): Message = manifest match {
    case None ⇒ persistent(mf.PersistentMessage.parseFrom(bytes))
    case Some(c) ⇒ c match {
      case PersistentImplClass              ⇒ persistent(mf.PersistentMessage.parseFrom(bytes))
      case PersistentReprClass              ⇒ persistent(mf.PersistentMessage.parseFrom(bytes))
      case AtomicWriteClass                 ⇒ atomicWrite(mf.AtomicWrite.parseFrom(bytes))
      case AtLeastOnceDeliverySnapshotClass ⇒ atLeastOnceDeliverySnapshot(mf.AtLeastOnceDeliverySnapshot.parseFrom(bytes))
      case PersistentStateChangeEventClass  ⇒ stateChange(mf.PersistentStateChangeEvent.parseFrom(bytes))
      case _                                ⇒ throw new IllegalArgumentException(s"Can't deserialize object of type ${c}")
    }
  }

  //
  // toBinary helpers
  //

  def atLeastOnceDeliverySnapshotBuilder(snap: AtLeastOnceDeliverySnapshot): mf.AtLeastOnceDeliverySnapshot.Builder = {
    val builder = mf.AtLeastOnceDeliverySnapshot.newBuilder
    builder.setCurrentDeliveryId(snap.currentDeliveryId)
    snap.unconfirmedDeliveries.foreach { unconfirmed ⇒
      val unconfirmedBuilder =
        mf.AtLeastOnceDeliverySnapshot.UnconfirmedDelivery.newBuilder.
          setDeliveryId(unconfirmed.deliveryId).
          setDestination(unconfirmed.destination.toString).
          setPayload(persistentPayloadBuilder(unconfirmed.message.asInstanceOf[AnyRef]))
      builder.addUnconfirmedDeliveries(unconfirmedBuilder)
    }
    builder
  }

  private[persistence] def stateChangeBuilder(stateChange: StateChangeEvent): mf.PersistentStateChangeEvent.Builder = {
    val builder = mf.PersistentStateChangeEvent.newBuilder.setStateIdentifier(stateChange.stateIdentifier)
    stateChange.timeout match {
      case None          ⇒ builder
      case Some(timeout) ⇒ builder.setTimeout(timeout.toString())
    }
  }

  def atLeastOnceDeliverySnapshot(atLeastOnceDeliverySnapshot: mf.AtLeastOnceDeliverySnapshot): AtLeastOnceDeliverySnapshot = {
    import scala.collection.JavaConverters._
    val unconfirmedDeliveries = new VectorBuilder[UnconfirmedDelivery]()
    atLeastOnceDeliverySnapshot.getUnconfirmedDeliveriesList().iterator().asScala foreach { next ⇒
      unconfirmedDeliveries += UnconfirmedDelivery(next.getDeliveryId, ActorPath.fromString(next.getDestination),
        payload(next.getPayload))
    }

    AtLeastOnceDeliverySnapshot(
      atLeastOnceDeliverySnapshot.getCurrentDeliveryId,
      unconfirmedDeliveries.result())
  }

  def stateChange(persistentStateChange: mf.PersistentStateChangeEvent): StateChangeEvent = {
    StateChangeEvent(
      persistentStateChange.getStateIdentifier,
      if (persistentStateChange.hasTimeout) Some(Duration(persistentStateChange.getTimeout).asInstanceOf[duration.FiniteDuration]) else None)
  }

  private def atomicWriteBuilder(a: AtomicWrite) = {
    val builder = mf.AtomicWrite.newBuilder
    a.payload.foreach { p ⇒
      builder.addPayload(persistentMessageBuilder(p))
    }
    builder
  }

  private def persistentMessageBuilder(persistent: PersistentRepr) = {
    val builder = mf.PersistentMessage.newBuilder

    if (persistent.persistenceId != Undefined) builder.setPersistenceId(persistent.persistenceId)
    if (persistent.sender != Actor.noSender) builder.setSender(Serialization.serializedActorPath(persistent.sender))
    if (persistent.manifest != PersistentRepr.Undefined) builder.setManifest(persistent.manifest)

    builder.setPayload(persistentPayloadBuilder(persistent.payload.asInstanceOf[AnyRef]))
    builder.setSequenceNr(persistent.sequenceNr)
    // deleted is not used in new records from 2.4
    if (persistent.writerUuid != Undefined) builder.setWriterUuid(persistent.writerUuid)
    builder
  }

  private def persistentPayloadBuilder(payload: AnyRef) = {
    def payloadBuilder() = {
      val serializer = serialization.findSerializerFor(payload)
      val builder = mf.PersistentPayload.newBuilder()

      serializer match {
        case ser2: SerializerWithStringManifest ⇒
          val manifest = ser2.manifest(payload)
          if (manifest != PersistentRepr.Undefined)
            builder.setPayloadManifest(ByteString.copyFromUtf8(manifest))
        case _ ⇒
          if (serializer.includeManifest)
            builder.setPayloadManifest(ByteString.copyFromUtf8(payload.getClass.getName))
      }

      builder.setPayload(ByteString.copyFrom(serializer.toBinary(payload)))
      builder.setSerializerId(serializer.identifier)
      builder
    }

    // serialize actor references with full address information (defaultAddress)
    transportInformation match {
      case Some(ti) ⇒ Serialization.currentTransportInformation.withValue(ti) { payloadBuilder() }
      case None     ⇒ payloadBuilder()
    }
  }

  //
  // fromBinary helpers
  //

  private def persistent(persistentMessage: mf.PersistentMessage): PersistentRepr = {
    PersistentRepr(
      payload(persistentMessage.getPayload),
      persistentMessage.getSequenceNr,
      if (persistentMessage.hasPersistenceId) persistentMessage.getPersistenceId else Undefined,
      if (persistentMessage.hasManifest) persistentMessage.getManifest else Undefined,
      if (persistentMessage.hasDeleted) persistentMessage.getDeleted else false,
      if (persistentMessage.hasSender) system.provider.resolveActorRef(persistentMessage.getSender) else Actor.noSender,
      if (persistentMessage.hasWriterUuid) persistentMessage.getWriterUuid else Undefined)
  }

  private def atomicWrite(atomicWrite: mf.AtomicWrite): AtomicWrite = {
    import scala.collection.JavaConverters._
    AtomicWrite(atomicWrite.getPayloadList.asScala.map(persistent)(collection.breakOut))
  }

  private def payload(persistentPayload: mf.PersistentPayload): Any = {
    val manifest = if (persistentPayload.hasPayloadManifest)
      persistentPayload.getPayloadManifest.toStringUtf8 else ""

    serialization.deserialize(
      persistentPayload.getPayload.toByteArray,
      persistentPayload.getSerializerId,
      manifest).get
  }

}
