/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.cluster.MemberStatus
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
import akka.cluster.sharding.typed.scaladsl.StartEntity
import akka.cluster.typed.Cluster
import akka.cluster.typed.SelfUp
import akka.cluster.typed.Subscribe
import akka.stream.scaladsl.Source

import java.util.Optional
import java.util.function.IntFunction
import scala.compat.java8.OptionConverters._
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessImpl {

  object KeepAlivePinger {
    sealed trait Event
    private case object Tick extends Event
    private case object SendKeepAliveDone extends Event

    def apply[T](
        settings: ShardedDaemonProcessSettings,
        name: String,
        identities: Set[EntityId],
        shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[Event] = {
      val sortedIdentities = identities.toVector.sorted

      def sendKeepAliveMessages()(implicit sys: ActorSystem[_]): Future[Done] = {
        if (settings.keepAliveThrottleInterval == Duration.Zero) {
          sortedIdentities.foreach(id => shardingRef ! StartEntity(id))
          Future.successful(Done)
        } else {
          Source(sortedIdentities).throttle(1, settings.keepAliveThrottleInterval).runForeach { id =>
            shardingRef ! StartEntity(id)
          }
        }
      }

      Behaviors.setup[Event] { context =>
        implicit val system: ActorSystem[_] = context.system
        val cluster = Cluster(system)

        if (cluster.selfMember.status == MemberStatus.Up)
          context.self ! Tick
        else
          cluster.subscriptions ! Subscribe(context.messageAdapter[SelfUp](_ => Tick), classOf[SelfUp])

        def isActive(): Boolean = {
          val members = settings.role match {
            case None       => cluster.state.members
            case Some(role) => cluster.state.members.filter(_.roles.contains(role))
          }
          // members are sorted so this is deterministic (the same) on all nodes
          members.take(settings.keepAliveFromNumberOfNodes).contains(cluster.selfMember)
        }

        Behaviors.withTimers { timers =>
          Behaviors.receiveMessage {
            case Tick =>
              if (isActive()) {
                context.log.debug2(
                  s"Sending periodic keep alive for Sharded Daemon Process [{}] to [{}] processes.",
                  name,
                  sortedIdentities.size)
                context.pipeToSelf(sendKeepAliveMessages()) { _ =>
                  SendKeepAliveDone
                }
              } else {
                timers.startSingleTimer(Tick, settings.keepAliveInterval)
              }
              Behaviors.same
            case SendKeepAliveDone =>
              timers.startSingleTimer(Tick, settings.keepAliveInterval)
              Behaviors.same
          }
        }
      }
    }

  }

  final class MessageExtractor[T] extends ShardingMessageExtractor[ShardingEnvelope[T], T] {
    def entityId(message: ShardingEnvelope[T]): String = message match {
      case ShardingEnvelope(id, _) => id
    }

    def shardId(entityId: String): String = entityId

    def unwrapMessage(message: ShardingEnvelope[T]): T = message.message
  }

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
      implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] =
    init(name, numberOfInstances, behaviorFactory, ShardedDaemonProcessSettings(system), None, None)(classTag)

  override def init[T](name: String, numberOfInstances: Int, behaviorFactory: Int => Behavior[T], stopMessage: T)(
      implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] =
    init(name, numberOfInstances, behaviorFactory, ShardedDaemonProcessSettings(system), Some(stopMessage), None)(
      classTag)

  def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T])(implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] =
    init(name, numberOfInstances, behaviorFactory, settings, stopMessage, None)

  def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T],
      shardAllocationStrategy: Option[ShardAllocationStrategy])(
      implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] =
    initWithContext(
      name,
      numberOfInstances,
      context => behaviorFactory(context.processNumber),
      settings,
      stopMessage,
      None)

  def initWithContext[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: ShardedDaemonProcessContext => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T],
      shardAllocationStrategy: Option[ShardAllocationStrategy])(
      implicit classTag: ClassTag[T]): ActorRef[ShardedDaemonProcessCommand] = {

    val entityTypeKey = EntityTypeKey[T](s"sharded-daemon-process-$name")

    // One shard per actor identified by the numeric id encoded in the entity id
    val numberOfShards = numberOfInstances
    val entityIds = (0 until numberOfInstances).map(_.toString)

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
    if (shardingSettings.role.forall(nodeRoles)) {
      val entity = Entity(entityTypeKey) { ctx =>
        // FIXME when we actually re-scale we need to encode numberOfInstances in entityId
        behaviorFactory(ShardedDaemonProcessContextImpl(ctx.entityId.toInt, numberOfInstances, name))
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

      system.systemActorOf(
        KeepAlivePinger(settings, name, entityIds.toSet, shardingRef),
        s"ShardedDaemonProcessKeepAlive-$name")
    }
    // FIXME access to coordinator singleton
    system.deadLetters
  }

  // Java API
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]]): ActorRef[ShardedDaemonProcessCommand] =
    init(name, numberOfInstances, n => behaviorFactory(n))(ClassTag(messageClass))

  override def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      stopMessage: T): ActorRef[ShardedDaemonProcessCommand] =
    init(
      name,
      numberOfInstances,
      n => behaviorFactory(n),
      ShardedDaemonProcessSettings(system),
      Some(stopMessage),
      None)(ClassTag(messageClass))

  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T]): ActorRef[ShardedDaemonProcessCommand] =
    init(name, numberOfInstances, n => behaviorFactory(n), settings, stopMessage.asScala, None)(ClassTag(messageClass))

  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T],
      shardAllocationStrategy: Optional[ShardAllocationStrategy]): ActorRef[ShardedDaemonProcessCommand] =
    init(
      name,
      numberOfInstances,
      n => behaviorFactory(n),
      settings,
      stopMessage.asScala,
      shardAllocationStrategy.asScala)(ClassTag(messageClass))

  override def initWithContext[T](
      messageClass: Class[T],
      name: EntityId,
      numberOfInstances: Int,
      behaviorFactory: java.util.function.Function[ShardedDaemonProcessContext, Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T],
      shardAllocationStrategy: Optional[ShardAllocationStrategy]): ActorRef[ShardedDaemonProcessCommand] =
    initWithContext(
      messageClass,
      name,
      numberOfInstances,
      behaviorFactory,
      settings,
      stopMessage,
      shardAllocationStrategy)
}
