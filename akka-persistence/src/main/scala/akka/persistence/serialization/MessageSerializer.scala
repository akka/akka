/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.serialization

import scala.language.existentials
import com.google.protobuf._
import akka.actor.{ ActorPath, ExtendedActorSystem }
import akka.japi.Util.immutableSeq
import akka.persistence._
import akka.persistence.serialization.MessageFormats._
import akka.serialization._
import akka.persistence.AtLeastOnceDelivery.{ AtLeastOnceDeliverySnapshot ⇒ AtLeastOnceDeliverySnap }
import akka.persistence.AtLeastOnceDelivery.UnconfirmedDelivery
import scala.collection.immutable.VectorBuilder

/**
 * Marker trait for all protobuf-serializable messages in `akka.persistence`.
 */
trait Message extends Serializable

/**
 * Protobuf serializer for [[akka.persistence.PersistentRepr]] and [[akka.persistence.AtLeastOnceDelivery]] messages.
 */
class MessageSerializer(val system: ExtendedActorSystem) extends BaseSerializer {
  import PersistentRepr.Undefined

  val PersistentReprClass = classOf[PersistentRepr]
  val PersistentImplClass = classOf[PersistentImpl]
  val AtLeastOnceDeliverySnapshotClass = classOf[AtLeastOnceDeliverySnap]

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
    case p: PersistentRepr          ⇒ persistentMessageBuilder(p).build().toByteArray
    case a: AtLeastOnceDeliverySnap ⇒ atLeastOnceDeliverySnapshotBuilder(a).build.toByteArray
    case _                          ⇒ throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  /**
   * Deserializes persistent messages. Delegates deserialization of a persistent
   * message's payload to a matching `akka.serialization.Serializer`.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): Message = manifest match {
    case None ⇒ persistent(PersistentMessage.parseFrom(bytes))
    case Some(c) ⇒ c match {
      case PersistentImplClass              ⇒ persistent(PersistentMessage.parseFrom(bytes))
      case PersistentReprClass              ⇒ persistent(PersistentMessage.parseFrom(bytes))
      case AtLeastOnceDeliverySnapshotClass ⇒ atLeastOnceDeliverySnapshot(AtLeastOnceDeliverySnapshot.parseFrom(bytes))
      case _                                ⇒ throw new IllegalArgumentException(s"Can't deserialize object of type ${c}")
    }
  }

  //
  // toBinary helpers
  //

  def atLeastOnceDeliverySnapshotBuilder(snap: AtLeastOnceDeliverySnap): AtLeastOnceDeliverySnapshot.Builder = {
    val builder = AtLeastOnceDeliverySnapshot.newBuilder
    builder.setCurrentDeliveryId(snap.currentDeliveryId)
    snap.unconfirmedDeliveries.foreach { unconfirmed ⇒
      val unconfirmedBuilder =
        AtLeastOnceDeliverySnapshot.UnconfirmedDelivery.newBuilder.
          setDeliveryId(unconfirmed.deliveryId).
          setDestination(unconfirmed.destination.toString).
          setPayload(persistentPayloadBuilder(unconfirmed.message.asInstanceOf[AnyRef]))
      builder.addUnconfirmedDeliveries(unconfirmedBuilder)
    }
    builder
  }

  def atLeastOnceDeliverySnapshot(atLeastOnceDeliverySnapshot: AtLeastOnceDeliverySnapshot): AtLeastOnceDeliverySnap = {
    import scala.collection.JavaConverters._
    val unconfirmedDeliveries = new VectorBuilder[UnconfirmedDelivery]()
    atLeastOnceDeliverySnapshot.getUnconfirmedDeliveriesList().iterator().asScala foreach { next ⇒
      unconfirmedDeliveries += UnconfirmedDelivery(next.getDeliveryId, ActorPath.fromString(next.getDestination),
        payload(next.getPayload))
    }

    AtLeastOnceDeliverySnap(
      atLeastOnceDeliverySnapshot.getCurrentDeliveryId,
      unconfirmedDeliveries.result())
  }

  private def persistentMessageBuilder(persistent: PersistentRepr) = {
    val builder = PersistentMessage.newBuilder

    if (persistent.persistenceId != Undefined) builder.setPersistenceId(persistent.persistenceId)
    if (persistent.sender != null) builder.setSender(Serialization.serializedActorPath(persistent.sender))

    builder.setPayload(persistentPayloadBuilder(persistent.payload.asInstanceOf[AnyRef]))
    builder.setSequenceNr(persistent.sequenceNr)
    builder.setDeleted(persistent.deleted)
    builder
  }

  private def persistentPayloadBuilder(payload: AnyRef) = {
    def payloadBuilder() = {
      val serializer = serialization.findSerializerFor(payload)
      val builder = PersistentPayload.newBuilder()

      serializer match {
        case ser2: SerializerWithStringManifest ⇒
          val manifest = ser2.manifest(payload)
          if (manifest != "")
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

  private def persistent(persistentMessage: PersistentMessage): PersistentRepr = {
    PersistentRepr(
      payload(persistentMessage.getPayload),
      persistentMessage.getSequenceNr,
      if (persistentMessage.hasPersistenceId) persistentMessage.getPersistenceId else Undefined,
      persistentMessage.getDeleted,
      if (persistentMessage.hasSender) system.provider.resolveActorRef(persistentMessage.getSender) else null)
  }

  private def payload(persistentPayload: PersistentPayload): Any = {
    val manifest = if (persistentPayload.hasPayloadManifest)
      persistentPayload.getPayloadManifest.toStringUtf8 else ""

    serialization.deserialize(
      persistentPayload.getPayload.toByteArray,
      persistentPayload.getSerializerId,
      manifest).get
  }

}
