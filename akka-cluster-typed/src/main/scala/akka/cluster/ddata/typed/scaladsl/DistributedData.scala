/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.typed.Props
import akka.cluster.{ ddata ⇒ dd }
import akka.cluster.typed.{ Cluster ⇒ ClusterT }
import akka.cluster.ddata.SelfUniqueAddress

object DistributedData extends ExtensionId[DistributedData] {
  def get(system: ActorSystem[_]): DistributedData = apply(system)

  override def createExtension(system: ActorSystem[_]): DistributedData =
    new DistributedData(system)
}

/**
 * Akka extension for convenient configuration and use of the
 * [[Replicator]]. Configuration settings are defined in the
 * `akka.cluster.ddata` section, see `reference.conf`.
 *
 * This is using the same underlying `Replicator` instance as
 * [[akka.cluster.ddata.DistributedData]] and that means that typed
 * and untyped actors can share the same data.
 */
class DistributedData(system: ActorSystem[_]) extends Extension {
  import akka.actor.typed.scaladsl.adapter._

  private val settings: ReplicatorSettings = ReplicatorSettings(system)

  private val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]

  implicit val selfUniqueAddress: SelfUniqueAddress = dd.DistributedData(untypedSystem).selfUniqueAddress

  /**
   * `ActorRef` of the [[Replicator]] .
   */
  val replicator: ActorRef[Replicator.Command] =
    if (isTerminated) {
      system.log.warning("Replicator points to dead letters: Make sure the cluster node is not terminated and has the proper role!")
      system.deadLetters
    } else {
      val underlyingReplicator = dd.DistributedData(untypedSystem).replicator
      val replicatorBehavior = Replicator.behavior(settings, underlyingReplicator)

      system.internalSystemActorOf(replicatorBehavior, ReplicatorSettings.name(system), Props.empty)
    }

  /**
   * Returns true if this member is not tagged with the role configured for the replicas.
   */
  private def isTerminated: Boolean = dd.DistributedData(system.toUntyped).isTerminated

}

