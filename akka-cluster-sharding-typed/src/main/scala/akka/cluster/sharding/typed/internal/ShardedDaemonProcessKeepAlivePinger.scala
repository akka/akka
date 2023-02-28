/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.TimerScheduler
import akka.annotation.InternalApi
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.StartEntity
import akka.cluster.typed.Cluster
import akka.cluster.typed.SelfUp
import akka.cluster.typed.Subscribe
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessKeepAlivePinger {

  def serviceKeyFor(name: String): ServiceKey[Message] =
    ServiceKey[Message](s"sharded-daemon-process-keepalive-$name")

  sealed trait Message

  // FIXME do we need acks for stop/start?
  final case class Pause(revision: Int) extends Message

  final case class Start(revision: Int) extends Message

  private final case class Tick(revision: Int) extends Message

  private case class SendKeepAliveDone(revision: Int) extends Message

  def apply[T](
      settings: ShardedDaemonProcessSettings,
      daemonProcessName: String,
      initialNumberOfInstances: Int,
      shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[Message] =
    Behaviors.setup[Message] { context =>
      Behaviors.withTimers { timers =>
        new ShardedDaemonProcessKeepAlivePinger(
          settings,
          context,
          timers,
          daemonProcessName,
          initialNumberOfInstances,
          shardingRef).init()
      }
    }
}

private final class ShardedDaemonProcessKeepAlivePinger[T](
    settings: ShardedDaemonProcessSettings,
    context: ActorContext[ShardedDaemonProcessKeepAlivePinger.Message],
    timers: TimerScheduler[ShardedDaemonProcessKeepAlivePinger.Message],
    daemonProcessName: String,
    initialNumberOfInstances: Int,
    shardingRef: ActorRef[ShardingEnvelope[T]]) {
  import ShardedDaemonProcessKeepAlivePinger._

  private val cluster = Cluster(context.system)

  def init(): Behavior[Message] = {
    val initialRevision = 0

    // register to receptionist so that coordinator can reach us
    val serviceKey = serviceKeyFor(daemonProcessName)
    context.system.receptionist ! Receptionist.Register(serviceKey, context.self)

    // defer first tick until our cluster node is up
    // FIXME can revision change before first tick arrives so that it is discarded?
    if (cluster.selfMember.status == MemberStatus.Up)
      context.self ! Tick(initialRevision)
    else
      cluster.subscriptions ! Subscribe(context.messageAdapter[SelfUp](_ => Tick(initialRevision)), classOf[SelfUp])

    start(initialRevision)
  }

  private def start(currentRevision: Int): Behavior[Message] = {
    val sortedIdentities = sortedIdentitiesFor(currentRevision)

    Behaviors.receiveMessage {
      case Tick(`currentRevision`) =>
        if (shouldPing()) {
          context.log.debug2(
            s"Sending periodic keep alive for Sharded Daemon Process [{}] to [{}] processes.",
            daemonProcessName,
            sortedIdentities.size)
          context.pipeToSelf(sendKeepAliveMessages(sortedIdentities)) { _ =>
            SendKeepAliveDone(currentRevision)
          }
        } else {
          timers.startSingleTimer(Tick, Tick(currentRevision), settings.keepAliveInterval)
        }
        Behaviors.same
      case SendKeepAliveDone(`currentRevision`) =>
        timers.startSingleTimer(Tick, Tick(currentRevision), settings.keepAliveInterval)
        Behaviors.same
      case Pause(revision) =>
        context.log.debug2("Pausing sharded daemon process pinger [{}] (revision [{}]", daemonProcessName, revision)
        if (revision <= currentRevision) {
          timers.cancel(Tick)
        }
        paused(revision)

      case SendKeepAliveDone(oldRevision) =>
        context.log.debugN(
          "KeepAlive for [{}] got SendKeepAliveDone from old revision [{}], current is [{}], ignoring",
          daemonProcessName,
          oldRevision,
          currentRevision)
        Behaviors.ignore

      case Tick(oldRevision) =>
        context.log.debugN(
          "Keep alive for [{}] got tick from old revision [{}], current is [{}], ignoring",
          daemonProcessName,
          oldRevision,
          currentRevision)
        Behaviors.ignore
      case Start(revision) =>
        context.log.debug2(
          "Unexpected start message for sharded daemon process pinger [{}] (revision [{}]",
          daemonProcessName,
          revision)
        Behaviors.ignore

    }
  }

  /**
   * Rescale in progress, don't ping workers until coordinator says so
   */
  private def paused(pausedRevision: Int): Behavior[Message] = Behaviors.receiveMessage {
    case Pause(_) => Behaviors.same
    case Start(revision) if revision >= pausedRevision =>
      context.log
        .debug2("Un-pausing sharded daemon process pinger [{}] (revision [{}]", daemonProcessName, pausedRevision)
      context.self ! Tick(pausedRevision)
      start(pausedRevision)

    case Start(revision) =>
      context.log.warn2(
        "Paused sharded daemon process pinger [{}] got unexpected start for old revision [{}], ignoring",
        daemonProcessName,
        revision)
      Behaviors.ignore
    case Tick(_)              => Behaviors.ignore
    case SendKeepAliveDone(_) => Behaviors.ignore
  }

  private def shouldPing(): Boolean = {
    val members = settings.role match {
      case None       => cluster.state.members
      case Some(role) => cluster.state.members.filter(_.roles.contains(role))
    }
    // members are sorted so this is deterministic (the same) on all nodes
    members.take(settings.keepAliveFromNumberOfNodes).contains(cluster.selfMember)
  }

  private def sortedIdentitiesFor(revision: Int) =
    (0 until initialNumberOfInstances)
      .map(n => ShardedDaemonProcessImpl.DecodedId(revision, initialNumberOfInstances, n).encodeEntityId)
      .toVector
      .sorted

  private def sendKeepAliveMessages(sortedIdentities: Vector[String]): Future[Done] = {
    if (settings.keepAliveThrottleInterval == Duration.Zero) {
      sortedIdentities.foreach(id => shardingRef ! StartEntity(id))
      Future.successful(Done)
    } else {
      implicit val system: ActorSystem[_] = context.system
      Source(sortedIdentities).throttle(1, settings.keepAliveThrottleInterval).runForeach { id =>
        shardingRef ! StartEntity(id)
      }
    }
  }
}
