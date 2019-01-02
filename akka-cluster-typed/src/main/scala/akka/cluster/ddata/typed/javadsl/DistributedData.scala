/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.ActorRef
import akka.actor.typed.ExtensionSetup
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.cluster.ddata.SelfUniqueAddress

object DistributedData extends ExtensionId[DistributedData] {
  def get(system: ActorSystem[_]): DistributedData = apply(system)

  override def createExtension(system: ActorSystem[_]): DistributedData =
    new DistributedDataImpl(system)
}

/**
 * Akka extension for convenient configuration and use of the
 * [[Replicator]]. Configuration settings are defined in the
 * `akka.cluster.ddata` section, see `reference.conf`.
 *
 * This is using the same underlying `Replicator` instance as
 * [[akka.cluster.ddata.DistributedData]] and that means that typed
 * and untyped actors can share the same data.
 *
 * This class is not intended for user extension other than for test purposes (e.g.
 * stub implementation). More methods may be added in the future and that may break
 * such implementations.
 */
@DoNotInherit
abstract class DistributedData extends Extension {
  /**
   * `ActorRef` of the [[Replicator]] .
   */
  def replicator: ActorRef[Replicator.Command]

  def selfUniqueAddress: SelfUniqueAddress
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class DistributedDataImpl(system: ActorSystem[_]) extends DistributedData {

  override val replicator: ActorRef[Replicator.Command] =
    akka.cluster.ddata.typed.scaladsl.DistributedData(system).replicator.narrow[Replicator.Command]

  override val selfUniqueAddress: SelfUniqueAddress =
    akka.cluster.ddata.typed.scaladsl.DistributedData(system).selfUniqueAddress

}

object DistributedDataSetup {
  def apply[T <: Extension](createExtension: ActorSystem[_] ⇒ DistributedData): DistributedDataSetup =
    new DistributedDataSetup(new java.util.function.Function[ActorSystem[_], DistributedData] {
      override def apply(sys: ActorSystem[_]): DistributedData = createExtension(sys)
    }) // TODO can be simplified when compiled only with Scala >= 2.12

}

/**
 * Can be used in [[akka.actor.setup.ActorSystemSetup]] when starting the [[ActorSystem]]
 * to replace the default implementation of the [[DistributedData]] extension. Intended
 * for tests that need to replace extension with stub/mock implementations.
 */
final class DistributedDataSetup(createExtension: java.util.function.Function[ActorSystem[_], DistributedData])
  extends ExtensionSetup[DistributedData](DistributedData, createExtension)
