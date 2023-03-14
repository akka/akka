/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.TimerScheduler
import akka.annotation.InternalApi
import akka.cluster.MemberStatus
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.internal.ShardedDaemonProcessState.Register
import akka.cluster.sharding.typed.scaladsl.StartEntity
import akka.cluster.typed.Cluster
import akka.cluster.typed.SelfUp
import akka.cluster.typed.Subscribe
import akka.pattern.StatusReply
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessKeepAlivePinger {

  /**
   * Pub sub topic to reach all keep alive pingers for a given sharded daemon process name
   */
  def topicFor(name: String): Behavior[Topic.Command[Message]] =
    Topic[Message](s"sharded-daemon-process-keepalive-$name")

  sealed trait Message

  // control from the coordinator
  final case class Pause(revision: Long, replyTo: ActorRef[StatusReply[Paused]])
      extends Message
      with ClusterShardingTypedSerializable
  final case class Paused(pingerActor: ActorRef[Message]) extends ClusterShardingTypedSerializable

  final case class Resume(revision: Long, newNumberOfProcesses: Int, replyTo: ActorRef[StatusReply[Resumed]])
      extends Message
      with ClusterShardingTypedSerializable
  final case class Resumed(pingerActor: ActorRef[Message]) extends ClusterShardingTypedSerializable

  // internal messages
  private final case class Tick(revision: Long) extends Message

  private final case class SendKeepAliveDone(revision: Long) extends Message

  private final case class InternalGetResponse(rsp: Replicator.GetResponse[Register]) extends Message

  def apply[T](
      settings: ShardedDaemonProcessSettings,
      daemonProcessName: String,
      supportsRescale: Boolean,
      initialNumberOfInstances: Int,
      shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[Message] = {
    Behaviors
      .supervise(Behaviors.setup[Message] { context =>
        Behaviors.withTimers { timers =>
          new ShardedDaemonProcessKeepAlivePinger(
            settings,
            supportsRescale,
            context,
            timers,
            daemonProcessName,
            initialNumberOfInstances,
            shardingRef).init()
        }
      })
      .onFailure(SupervisorStrategy.restart)
  }
}

private final class ShardedDaemonProcessKeepAlivePinger[T](
    settings: ShardedDaemonProcessSettings,
    supportsRescale: Boolean,
    context: ActorContext[ShardedDaemonProcessKeepAlivePinger.Message],
    timers: TimerScheduler[ShardedDaemonProcessKeepAlivePinger.Message],
    daemonProcessName: String,
    initialNumberOfInstances: Int,
    shardingRef: ActorRef[ShardingEnvelope[T]]) {
  import ShardedDaemonProcessKeepAlivePinger._
  import ShardedDaemonProcessState._

  private val cluster = Cluster(context.system)
  private lazy val ddataKey = ShardedDaemonProcessState.ddataKey(daemonProcessName)

  private def startTicks(revision: Long): Unit = {
    // defer first tick until our cluster node is up
    if (cluster.selfMember.status == MemberStatus.Up)
      context.self ! Tick(revision)
    else
      cluster.subscriptions ! Subscribe(context.messageAdapter[SelfUp](_ => Tick(revision)), classOf[SelfUp])
  }

  private def subscribeToTopic(): Unit = {
    // register subscribe to topic so that coordinator can reach us
    val topic = context.spawn(topicFor(daemonProcessName), "topic")
    topic ! Topic.Subscribe(context.self)
  }

  def init(): Behavior[Message] = {
    if (!supportsRescale) {
      startTicks(startRevision)
      start(startRevision, initialNumberOfInstances)
    } else {
      // we have to check ddata state for current revision and number of workers
      DistributedData.withReplicatorMessageAdapter[Message, Register] { replicatorAdapter =>
        replicatorAdapter.askGet(
          replyTo => Replicator.Get(ddataKey, Replicator.ReadMajority(settings.rescaleReadStateTimeout), replyTo),
          response => InternalGetResponse(response))

        waitForInitialDdataState()
      }
    }
  }

  private def waitForInitialDdataState(): Behavior[Message] = {

    Behaviors.receiveMessagePartial {
      case InternalGetResponse(success @ Replicator.GetSuccess(`ddataKey`)) =>
        val state = success.get(ddataKey).value
        subscribeToTopic()
        if (state.completed) {
          context.log.debugN(
            "{}: Starting pinger with [{}] workers, revision [{}] from [{}]",
            daemonProcessName,
            state.numberOfProcesses,
            state.revision,
            state.started)
          startTicks(state.revision)
          start(state.revision, state.numberOfProcesses)
        } else {
          context.log.debugN(
            "{}: Pinger found non completed state with [{}] workers, revision [{}] from [{}]",
            daemonProcessName,
            state.revision,
            state.started)
          // FIXME what about ticks here?
          // FIXME is pausing the right thing?
          paused(state.revision - 1)
        }

      case InternalGetResponse(_ @Replicator.NotFound(`ddataKey`)) =>
        context.log.debug(
          "{}: No previous state stored for Sharded Daemon Process, starting from revision [0] with the initial [{}] workers",
          daemonProcessName,
          initialNumberOfInstances)
        subscribeToTopic()
        startTicks(startRevision)
        start(startRevision, numberOfProcesses = initialNumberOfInstances)

      case InternalGetResponse(_ @Replicator.GetFailure(`ddataKey`)) =>
        context.log
          .debug("{}: Failed fetching initial state for Sharded Daemon Process pinger, retrying", daemonProcessName)
        init()

    }

  }

  private def start(currentRevision: Long, numberOfProcesses: Int): Behavior[Message] = {
    val sortedIdentities =
      ShardedDaemonProcessId.sortedIdentitiesFor(currentRevision, numberOfProcesses, supportsRescale)

    Behaviors.receiveMessage {
      case Tick(`currentRevision`) =>
        if (shouldPing()) {
          context.log.debugN(
            s"Sending periodic keep alive for Sharded Daemon Process [{}] to [{}] processes (revision [{}]).",
            daemonProcessName,
            sortedIdentities.size,
            currentRevision)
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
      case Pause(revision, replyTo) =>
        context.log.debug2("Pausing sharded daemon process pinger [{}] (revision [{}])", daemonProcessName, revision)
        if (revision <= currentRevision) {
          timers.cancel(Tick)
        }
        replyTo ! StatusReply.Success(Paused(context.self))
        paused(revision)

      case SendKeepAliveDone(oldRevision) =>
        context.log.debugN(
          "KeepAlive for [{}] got SendKeepAliveDone from old revision [{}], current is [{}], ignoring",
          daemonProcessName,
          oldRevision,
          currentRevision)
        Behaviors.same

      case Tick(oldRevision) =>
        context.log.debugN(
          "Keep alive for [{}] got tick from old revision [{}], current is [{}], ignoring",
          daemonProcessName,
          oldRevision,
          currentRevision)
        Behaviors.same
      case Resume(`currentRevision`, _, replyTo) =>
        context.log.debug2(
          "Sharded daemon process pinger [{}] got start for already started revision (revision [{}]",
          daemonProcessName,
          currentRevision)
        replyTo ! StatusReply.Success(Resumed(context.self))
        Behaviors.same

      case Resume(otherRevision, _, replyTo) =>
        context.log.debugN(
          "Sharded daemon process pinger [{}] got start for unexpected revision (revision [{}], current [{}])",
          daemonProcessName,
          otherRevision,
          currentRevision)
        replyTo ! StatusReply.Success(Resumed(context.self))
        Behaviors.same

      case unexpected =>
        context.log.warn2(
          "{}: Unexpected message to pinger in state 'start': {}",
          daemonProcessName,
          unexpected.getClass)
        Behaviors.unhandled

    }
  }

  /**
   * Rescale in progress, don't ping workers until coordinator says so
   */
  private def paused(pausedRevision: Long): Behavior[Message] = Behaviors.receiveMessage {
    case Pause(_, replyTo) =>
      replyTo ! StatusReply.Success(Paused(context.self))
      Behaviors.same
    case Resume(revision, newNumberOfProcesses, replyTo) =>
      if (revision >= pausedRevision) {
        context.log.debug2("{}: Un-pausing sharded daemon process pinger (revision [{}])", daemonProcessName, revision)
        replyTo ! StatusReply.Success(Resumed(context.self))
        context.self ! Tick(revision)
        start(revision, newNumberOfProcesses)
      } else {
        context.log.warn2(
          "{}, Paused sharded daemon process pinger, got unexpected start for old revision [{}], ignoring",
          daemonProcessName,
          revision)
        replyTo ! StatusReply.Error("Old revision")
        Behaviors.same
      }

    case Tick(_)              => Behaviors.same
    case SendKeepAliveDone(_) => Behaviors.same

    case unexpected =>
      context.log
        .warn2("{}: Unexpected message to pinger in state 'paused': {}", daemonProcessName, unexpected.getClass)
      Behaviors.unhandled
  }

  private def shouldPing(): Boolean = {
    val members = settings.role match {
      case None       => cluster.state.members
      case Some(role) => cluster.state.members.filter(_.roles.contains(role))
    }
    // members are sorted so this is deterministic (the same) on all nodes
    members.take(settings.keepAliveFromNumberOfNodes).contains(cluster.selfMember)
  }

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
