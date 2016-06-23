/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ ActorRef, Address }
import akka.remote.UniqueAddress
import akka.remote.artery.ControlMessage

// FIXME serialization 
/** INTERNAL API */
object CompressionProtocol {

  /** INTERNAL API */
  sealed trait CompressionMessage

  /**
   * INTERNAL API
   * Sent by the "receiving" node after allocating a compression id to a given [[akka.actor.ActorRef]]
   */
  private[remote] final case class ActorRefCompressionAdvertisement(from: UniqueAddress, ref: ActorRef, id: Int)
    extends ControlMessage with CompressionMessage

  /**
   * INTERNAL API
   * Sent by the "receiving" node after allocating a compression id to a given class manifest
   */
  private[remote] final case class ClassManifestCompressionAdvertisement(from: UniqueAddress, manifest: String, id: Int)
    extends ControlMessage with CompressionMessage

  /** INTERNAL API */
  private[akka] object Events {
    /** INTERNAL API */
    private[akka] sealed trait Event

    /** INTERNAL API */
    final case class HeavyHitterDetected(key: Any, id: Int, count: Long) extends Event

    /** INTERNAL API */
    final case class ReceivedCompressionAdvertisement(from: UniqueAddress, key: Any, id: Int) extends Event

  }

}
