/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import akka.actor.typed.{ ActorRef, ActorSystem, Extension, ExtensionId, Props }
import akka.actor.ExtendedActorSystem
import akka.cluster.{ ddata ⇒ dd }
import akka.actor.typed.scaladsl.adapter._

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
class DistributedData(system: ActorSystem[_])
  extends dd.AbstractDistributedData(system.toUntyped.asInstanceOf[ExtendedActorSystem])
  with Extension {

  /**
   * `ActorRef` of the [[Replicator]] .
   */
  val replicator: ActorRef[Replicator.Command] =
    if (isTerminated) {
      logWarnIfTerminated()
      system.deadLetters
    } else {
      val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]
      val underlyingReplicator = dd.DistributedData(untypedSystem).replicator
      val replicatorBehavior = Replicator.behavior(settings, underlyingReplicator)

      system.internalSystemActorOf(replicatorBehavior, name = replicatorName(Some("typed")), Props.empty)
    }

}

