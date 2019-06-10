/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import scala.concurrent.duration.FiniteDuration

import akka.actor.typed.{ ActorRef, ActorSystem, Extension, ExtensionId, Props }
import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.ReplicatedData
import akka.cluster.{ ddata => dd }
import akka.cluster.ddata.SelfUniqueAddress
import akka.util.JavaDurationConverters._

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

  /** INTERNAL API */
  @InternalApi private[akka] val unexpectedAskTimeout: FiniteDuration =
    system.settings.config
      .getDuration("akka.cluster.ddata.typed.replicator-message-adapter-unexpected-ask-timeout")
      .asScala

  private val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]

  implicit val selfUniqueAddress: SelfUniqueAddress = dd.DistributedData(untypedSystem).selfUniqueAddress

  /**
   * `ActorRef` of the [[Replicator]].
   *
   * @see [[DistributedData.replicatorMessageAdapter]]
   */
  val replicator: ActorRef[Replicator.Command] =
    if (isTerminated) {
      val log = system.log.withLoggerClass(getClass)
      if (Cluster(untypedSystem).isTerminated)
        log.warning("Replicator points to dead letters, because Cluster is terminated.")
      else
        log.warning(
          "Replicator points to dead letters. Make sure the cluster node has the proper role. " +
          "Node has roles [], Distributed Data is configured for roles []",
          Cluster(untypedSystem).selfRoles.mkString(","),
          settings.roles.mkString(","))
      system.deadLetters
    } else {
      val underlyingReplicator = dd.DistributedData(untypedSystem).replicator
      val replicatorBehavior = Replicator.behavior(settings, underlyingReplicator)

      system.internalSystemActorOf(
        replicatorBehavior,
        ReplicatorSettings.name(system),
        Props.empty.withDispatcherFromConfig(settings.dispatcher))
    }

  /**
   * When interacting with the [[DistributedData.replicator]] from an actor the [[ReplicatorMessageAdapter]]
   * provides convenient methods that adapts the response messages to the requesting actor's message protocol.
   *
   * One `ReplicatorMessageAdapter` instance can be used for a given `ReplicatedData` type,
   * e.g. an `OrSet[String]`. Interaction with several [[akka.cluster.ddata.Key]]s can be used via the same adapter
   * but they must all be of the same `ReplicatedData` type. For interaction with several different
   * `ReplicatedData` types, e.g. an `OrSet[String]` and a `GCounter`, an adapter can be created
   * for each type.
   *
   * @param context The [[ActorContext]] of the requesting actor.
   *
   * @tparam A Message type of the requesting actor.
   * @tparam B Type of the [[ReplicatedData]].
   */
  def replicatorMessageAdapter[A, B <: ReplicatedData](context: ActorContext[A]): ReplicatorMessageAdapter[A, B] =
    new ReplicatorMessageAdapter(context, replicator, unexpectedAskTimeout)

  /**
   * Returns true if this member is not tagged with the role configured for the replicas.
   */
  private def isTerminated: Boolean = dd.DistributedData(system.toUntyped).isTerminated

}
