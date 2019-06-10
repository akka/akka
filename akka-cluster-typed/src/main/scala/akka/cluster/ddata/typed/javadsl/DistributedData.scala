/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.ActorRef
import akka.actor.typed.ExtensionSetup
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.cluster.ddata.ReplicatedData
import akka.cluster.ddata.SelfUniqueAddress
import akka.util.JavaDurationConverters._

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
   * `ActorRef` of the [[Replicator]].
   *
   * @see [[DistributedData.replicatorMessageAdapter]]
   */
  def replicator: ActorRef[Replicator.Command]

  /**
   * When interacting with the [[DistributedData.replicator]] from an actor the [[ReplicatorMessageAdapter]]
   * provides convenient methods that adapts the response messages to the requesting actor's message protocol.
   *
   * One `ReplicatorMessageAdapter` instance can be used for a given `ReplicatedData` type,
   * e.g. an `OrSet<String>`. Interaction with several [[akka.cluster.ddata.Key]]s can be used via the same adapter
   * but they must all be of the same `ReplicatedData` type. For interaction with several different
   * `ReplicatedData` types, e.g. an `OrSet<String>` and a `GCounter`, an adapter can be created
   * for each type.
   *
   * @param context The [[ActorContext]] of the requesting actor.
   *
   * @tparam A Message type of the requesting actor.
   * @tparam B Type of the [[ReplicatedData]].
   */
  def replicatorMessageAdapter[A, B <: ReplicatedData](context: ActorContext[A]): ReplicatorMessageAdapter[A, B]

  def selfUniqueAddress: SelfUniqueAddress
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class DistributedDataImpl(system: ActorSystem[_]) extends DistributedData {

  private val unexpectedAskTimeout =
    akka.cluster.ddata.typed.scaladsl.DistributedData(system).unexpectedAskTimeout.asJava

  override val replicator: ActorRef[Replicator.Command] =
    akka.cluster.ddata.typed.scaladsl.DistributedData(system).replicator.narrow[Replicator.Command]

  override def replicatorMessageAdapter[A, B <: ReplicatedData](
      context: ActorContext[A]): ReplicatorMessageAdapter[A, B] =
    new ReplicatorMessageAdapter(context, replicator, unexpectedAskTimeout)

  override val selfUniqueAddress: SelfUniqueAddress =
    akka.cluster.ddata.typed.scaladsl.DistributedData(system).selfUniqueAddress

}

object DistributedDataSetup {
  def apply[T <: Extension](createExtension: ActorSystem[_] => DistributedData): DistributedDataSetup =
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
