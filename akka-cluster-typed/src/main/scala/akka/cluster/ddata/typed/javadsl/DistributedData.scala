/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.ActorRef

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

  /**
   * `ActorRef` of the [[Replicator]] .
   */
  val replicator: ActorRef[Replicator.Command] =
    akka.cluster.ddata.typed.scaladsl.DistributedData(system).replicator.narrow[Replicator.Command]

}

