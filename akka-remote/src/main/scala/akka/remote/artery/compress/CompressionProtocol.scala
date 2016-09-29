/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.ActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.ControlMessage

// FIXME serialization
/** INTERNAL API */
/**
 * INTERNAL API
 */
private[remote] object CompressionProtocol {

  /** INTERNAL API */
  sealed trait CompressionMessage

  /** INTERNAL API */
  sealed trait CompressionAdvertisement[T] extends ControlMessage with CompressionMessage {
    def from: UniqueAddress
    def table: CompressionTable[T]
  }

  /**
   * INTERNAL API
   * Sent by the "receiving" node after allocating a compression id to a given [[akka.actor.ActorRef]]
   */
  private[remote] final case class ActorRefCompressionAdvertisement(from: UniqueAddress, table: CompressionTable[ActorRef])
    extends CompressionAdvertisement[ActorRef]

  /**
   * INTERNAL API
   * Sent by the "sending" node after receiving [[ActorRefCompressionAdvertisement]]
   * The advertisement is also confirmed by the first message using that table version,
   * but we need separate ack in case the sender is not using any of the refs in the advertised
   * table.
   */
  private[remote] final case class ActorRefCompressionAdvertisementAck(from: UniqueAddress, tableVersion: Byte)
    extends ControlMessage with CompressionMessage

  /**
   * INTERNAL API
   * Sent by the "receiving" node after allocating a compression id to a given class manifest
   */
  private[remote] final case class ClassManifestCompressionAdvertisement(from: UniqueAddress, table: CompressionTable[String])
    extends CompressionAdvertisement[String]

  /**
   * INTERNAL API
   * Sent by the "sending" node after receiving [[ClassManifestCompressionAdvertisement]]
   * The advertisement is also confirmed by the first message using that table version,
   * but we need separate ack in case the sender is not using any of the refs in the advertised
   * table.
   */
  private[remote] final case class ClassManifestCompressionAdvertisementAck(from: UniqueAddress, tableVersion: Byte)
    extends ControlMessage with CompressionMessage

  /** INTERNAL API */
  private[remote] object Events {
    /** INTERNAL API */
    private[remote] sealed trait Event

    /** INTERNAL API */
    final case class HeavyHitterDetected(key: Any, id: Int, count: Long) extends Event

    /** INTERNAL API */
    final case class ReceivedActorRefCompressionTable(from: UniqueAddress, table: CompressionTable[ActorRef]) extends Event

    /** INTERNAL API */
    final case class ReceivedClassManifestCompressionTable(from: UniqueAddress, table: CompressionTable[String]) extends Event

  }

}
