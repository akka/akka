/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.annotation.InternalApi
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ClusterShardingSettings.RememberEntitiesStoreModeDData
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.ShardedDaemonProcessCommand
import akka.cluster.sharding.typed.ShardedDaemonProcessContext
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.javadsl
import akka.cluster.sharding.typed.scaladsl
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.typed.Cluster
import akka.cluster.typed.ClusterSingleton
import akka.cluster.typed.ClusterSingletonSettings
import akka.cluster.typed.SingletonActor

import java.util.Optional
import java.util.function.IntFunction
import scala.compat.java8.OptionConverters._
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessImpl {

  // entity id format: [r]|[total-count]|[process-n]
  val Separator = '|'
  def decodeEntityId(id: String) = {
    id.split(Separator) match {
      case Array(rev, count, n) => DecodedId(rev.toInt, count.toInt, n.toInt)
      case _                    => throw new IllegalArgumentException(s"Unexpected id for sharded daemon process: '$id'")
    }

  }
  final case class DecodedId(revision: Int, totalCount: Int, processNumber: Int) {
    def encodeEntityId: String = s"$revision$Separator$totalCount$Separator$processNumber"
  }

  final class MessageExtractor[T] extends ShardingMessageExtractor[ShardingEnvelope[T], T] {
    def entityId(message: ShardingEnvelope[T]): String = message match {
      case ShardingEnvelope(id, _) => id
    }

    // use process n for shard id
    def shardId(entityId: String): String = entityId.split(Separator)(2)

    def unwrapMessage(message: ShardingEnvelope[T]): T = message.message
  }

  def sortedIdentitiesFor(revision: Int, numberOfProcesses: Int): Vector[String] =
    (0 until numberOfProcesses)
      .map(n => ShardedDaemonProcessImpl.DecodedId(revision, numberOfProcesses, n).encodeEntityId)
      .toVector
      .sorted

  private val messageExtractor = new ShardedDaemonProcessImpl.MessageExtractor[Unit]

  def allShardsFor(revision: Int, numberOfProcesses: Int): Set[String] =
    ShardedDaemonProcessImpl.sortedIdentitiesFor(revision, numberOfProcesses).map(messageExtractor.shardId).toSet

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ShardedDaemonProcessImpl(system: ActorSystem[_])
    extends javadsl.ShardedDaemonProcess
    with scaladsl.ShardedDaemonProcess {

  private case class ShardedDaemonProcessContextImpl(processNumber: Int, totalProcesses: Int, name: String)
      extends ShardedDaemonProcessContext

  import ShardedDaemonProcessImpl._

  def init[T](name: String, numberOfInstances: Int, behaviorFactory: Int => Behavior[T])(
      implicit classTag: ClassTag[T]): Unit =
    init(name, numberOfInstances, behaviorFactory, ShardedDaemonProcessSettings(system), None, None)(classTag)

  override def init[T](name: String, numberOfInstances: Int, behaviorFactory: Int => Behavior[T], stopMessage: T)(
      implicit classTag: ClassTag[T]): Unit =
    init(name, numberOfInstances, behaviorFactory, ShardedDaemonProcessSettings(system), Some(stopMessage), None)(
      classTag)

  override def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T])(implicit classTag: ClassTag[T]): Unit =
    init(name, numberOfInstances, behaviorFactory, settings, stopMessage, None)

  override def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T],
      shardAllocationStrategy: Option[ShardAllocationStrategy])(implicit classTag: ClassTag[T]): Unit =
    internalInitWithContext(
      name,
      numberOfInstances,
      context => behaviorFactory(context.processNumber),
      settings,
      stopMessage,
      None,
      supportsRescale = false)

  override def initWithContext[T](
      name: EntityId,
      initialNumberOfInstances: Int,
      behaviorFactory: ShardedDaemonProcessContext => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: T)(implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] =
    initWithContext(name, initialNumberOfInstances, behaviorFactory, settings, Some(stopMessage), None)

  override def initWithContext[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: ShardedDaemonProcessContext => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T],
      shardAllocationStrategy: Option[ShardAllocationStrategy])(
      implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] =
    internalInitWithContext(
      name,
      numberOfInstances,
      behaviorFactory,
      settings,
      stopMessage,
      shardAllocationStrategy,
      supportsRescale = true)

  private def internalInitWithContext[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: ShardedDaemonProcessContext => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T],
      shardAllocationStrategy: Option[ShardAllocationStrategy],
      supportsRescale: Boolean)(implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] = {

    val entityTypeKey = EntityTypeKey[T](s"sharded-daemon-process-$name")

    // One shard per actor identified by the numeric id encoded in the entity id
    val numberOfShards = numberOfInstances

    val shardingSettings = {
      val shardingBaseSettings =
        settings.shardingSettings match {
          case None =>
            // defaults in akka.cluster.sharding but allow overrides specifically for sharded-daemon-process
            ClusterShardingSettings.fromConfig(
              system.settings.config.getConfig("akka.cluster.sharded-daemon-process.sharding"))
          case Some(shardingSettings) => shardingSettings
        }

      new ClusterShardingSettings(
        numberOfShards,
        if (settings.role.isDefined) settings.role else shardingBaseSettings.role,
        shardingBaseSettings.dataCenter,
        false, // remember entities disabled
        "",
        "",
        ClusterShardingSettings.PassivationStrategySettings.disabled, // passivation disabled
        shardingBaseSettings.shardRegionQueryTimeout,
        StateStoreModeDData,
        RememberEntitiesStoreModeDData, // not used as remembered entities is off
        shardingBaseSettings.tuningParameters,
        shardingBaseSettings.coordinatorSingletonOverrideRole,
        shardingBaseSettings.coordinatorSingletonSettings,
        shardingBaseSettings.leaseSettings)
    }

    val nodeRoles = Cluster(system).selfMember.roles
    val entity = Entity(entityTypeKey) { ctx =>
      // FIXME would it be too expensive here to check with the local replicator and verify the revision number
      // to never start if there is a higher rev number?
      val decodedId = decodeEntityId(ctx.entityId)
      behaviorFactory(ShardedDaemonProcessContextImpl(decodedId.processNumber, decodedId.totalCount, name))
    }.withSettings(shardingSettings).withMessageExtractor(new MessageExtractor)

    val entityWithStop = stopMessage match {
      case Some(stop) => entity.withStopMessage(stop)
      case None       => entity
    }

    val entityWithShardAllocationStrategy = shardAllocationStrategy match {
      case Some(strategy) => entityWithStop.withAllocationStrategy(strategy)
      case None           => entityWithStop
    }

    val shardingRef = ClusterSharding(system).init(entityWithShardAllocationStrategy)

    // only start pinger if role matches
    if (shardingSettings.role.forall(nodeRoles)) {
      system.systemActorOf(
        ShardedDaemonProcessKeepAlivePinger(settings, name, numberOfInstances, shardingRef),
        s"ShardedDaemonProcessKeepAlive-$name")
    }

    if (supportsRescale) {
      // FIXME do we need to start it on all nodes, regardless of role?
      DistributedData(system).replicator

      var singletonSettings =
        ClusterSingletonSettings(system)
      settings.role.foreach(role => singletonSettings = singletonSettings.withRole(role))
      val singleton =
        SingletonActor(
          ShardedDaemonProcessCoordinator(settings, shardingSettings, numberOfInstances, name, shardingRef),
          s"ShardedDaemonProcessCoordinator-$name").withSettings(singletonSettings)

      ClusterSingleton(system).init(singleton)
    } else {
      system.deadLetters
    }
  }

  // Java API
  override def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]]): Unit =
    init(name, numberOfInstances, n => behaviorFactory(n))(ClassTag(messageClass))

  override def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      stopMessage: T): Unit =
    init(
      name,
      numberOfInstances,
      n => behaviorFactory(n),
      ShardedDaemonProcessSettings(system),
      Some(stopMessage),
      None)(ClassTag(messageClass))

  override def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T]): Unit =
    init(name, numberOfInstances, n => behaviorFactory(n), settings, stopMessage.asScala, None)(ClassTag(messageClass))

  override def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T],
      shardAllocationStrategy: Optional[ShardAllocationStrategy]): Unit =
    init(
      name,
      numberOfInstances,
      n => behaviorFactory(n),
      settings,
      stopMessage.asScala,
      shardAllocationStrategy.asScala)(ClassTag(messageClass))

  override def initWithContext[T](
      messageClass: Class[T],
      name: String,
      initialNumberOfInstances: Int,
      behaviorFactory: java.util.function.Function[ShardedDaemonProcessContext, Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: T): ActorRef[ShardedDaemonProcessCommand] =
    initWithContext[T](
      messageClass,
      name,
      initialNumberOfInstances,
      behaviorFactory,
      settings,
      Optional.of(stopMessage),
      Optional.empty[ShardAllocationStrategy]())

  override def initWithContext[T](
      messageClass: Class[T],
      name: String,
      initialNumberOfInstances: Int,
      behaviorFactory: java.util.function.Function[ShardedDaemonProcessContext, Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T],
      shardAllocationStrategy: Optional[ShardAllocationStrategy]): ActorRef[ShardedDaemonProcessCommand] = {
    val classTag = ClassTag[T](messageClass)
    internalInitWithContext(
      name,
      initialNumberOfInstances,
      behaviorFactory.apply,
      settings,
      stopMessage.asScala,
      shardAllocationStrategy.asScala,
      supportsRescale = true)(classTag)
  }
}
