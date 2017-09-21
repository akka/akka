/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.scaladsl

import akka.typed.ActorSystem
import akka.typed.Extension
import akka.typed.ExtensionId
import akka.typed.ActorRef
import akka.actor.ExtendedActorSystem

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
 * [[akka.akka.cluster.ddata.DistributedData]] and that means that typed
 * and untyped actors can share the same data.
 */
class DistributedData(system: ActorSystem[_]) extends Extension {
  import akka.typed.scaladsl.adapter._

  private val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]
  private val config = system.settings.config.getConfig("akka.cluster.distributed-data")
  private val settings = ReplicatorSettings(config)

  /**
   * `ActorRef` of the [[Replicator]] .
   */
  val replicator: ActorRef[Replicator.Command] = {
    val configuredName = config.getString("name")
    val name = "typed" + configuredName.take(1).toUpperCase + configuredName.drop(1)

    val underlyingReplicator = akka.cluster.ddata.DistributedData(untypedSystem).replicator
    val replicatorBehavior = Replicator.behavior(settings, underlyingReplicator)

    untypedSystem.systemActorOf(PropsAdapter(replicatorBehavior), name)
  }

}

