/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.actor.ActorPath
import akka.actor.Address
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import akka.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.sharding.typed.ChangeNumberOfProcesses
import akka.cluster.sharding.typed.ShardedDaemonProcessCommand
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.internal.ShardedDaemonProcessKeepAlivePinger.Pause
import akka.cluster.sharding.typed.internal.ShardedDaemonProcessKeepAlivePinger.Restart
import akka.cluster.typed.Cluster
import akka.pattern.StatusReply

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * INTERNAL API
 *
 * A ShardedDaemonProcessCoordinator manages dynamic scaling of one setup of ShardedDaemonProcess
 */
private[akka] object ShardedDaemonProcessCoordinator {

  private final case class ScaleState(revision: Int, numberOfProcesses: Int, completed: Boolean, started: Instant)

  private type Register = LWWRegister[ScaleState]
  private def ddataKey(name: String) = LWWRegisterKey[ScaleState](name)
  private trait InternalMessage extends ShardedDaemonProcessCommand

  private case class InternalGetResponse(rsp: Replicator.GetResponse[Register]) extends InternalMessage

  private case class InternalUpdateResponse(rsp: Replicator.UpdateResponse[Register]) extends InternalMessage

  private case class PingerAck(actorPath: ActorPath) extends InternalMessage
  private case class PingerNack(ex: Throwable) extends InternalMessage

  private case object PauseAllKeepalivePingers extends InternalMessage
  private case object RestartAllKeepalivePingers extends InternalMessage
  private case class ShardStopped(shard: String) extends InternalMessage

  private object ShardStopTimeout extends InternalMessage

  def apply[T](
      settings: ShardedDaemonProcessSettings,
      initialNumberOfProcesses: Int,
      daemonProcessName: String,
      shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[ShardedDaemonProcessCommand] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.log.debug("ShardedDaemonProcessCoordinator for [{}] starting", daemonProcessName)
        val key = ddataKey(daemonProcessName)
        val ddata = DistributedData(context.system)
        val selfUniqueAddress = ddata.selfUniqueAddress

        DistributedData.withReplicatorMessageAdapter[ShardedDaemonProcessCommand, Register] { replicatorAdapter =>
          new ShardedDaemonProcessCoordinator(
            settings,
            context,
            timers,
            daemonProcessName,
            shardingRef.toClassic,
            initialNumberOfProcesses,
            key,
            selfUniqueAddress,
            replicatorAdapter).start()
        }
      }
    }
}

private class ShardedDaemonProcessCoordinator private (
    settings: ShardedDaemonProcessSettings,
    context: ActorContext[ShardedDaemonProcessCommand],
    timers: TimerScheduler[ShardedDaemonProcessCommand],
    daemonProcessName: String,
    shardingRef: akka.actor.ActorRef,
    initialNumberOfProcesses: Int,
    key: LWWRegisterKey[ShardedDaemonProcessCoordinator.ScaleState],
    selfUniqueAddress: SelfUniqueAddress,
    replicatorAdapter: ReplicatorMessageAdapter[ShardedDaemonProcessCommand, ShardedDaemonProcessCoordinator.Register]) {
  import ShardedDaemonProcessCoordinator._
  private val started = Instant.now()
  // FIXME write timeouts from config
  private val writeTimeout = 5.seconds
  private val readTimeout = 5.seconds

  // note this timeout needs to be long because waiting for process passivation should be set related to
  // the timeout for stopping shards in shard coordinator
  private val stopShardsTimeout = 1.minute
  private val pingerRetryTimeout = 3.seconds

  private val cluster = Cluster(context.system)

  // topic for broadcasting to keepalive pingers
  private val pingersTopic = context.spawn(ShardedDaemonProcessKeepAlivePinger.topicFor(daemonProcessName), "topic")

  private val pingerResponseAdapter = context.messageAdapter[StatusReply[ActorPath]] {
    case StatusReply.Success(path: ActorPath) => PingerAck(path)
    case StatusReply.Error(ex)                => PingerNack(ex)
    case _                                    => throw new IllegalArgumentException("Unexpected response") // compiler completeness pleaser
  }

  private val shardStoppedAdapter = context
    .messageAdapter[ShardCoordinator.Internal.ShardStopped] {
      case ShardCoordinator.Internal.ShardStopped(shard) => ShardStopped(shard)
    }
    .toClassic

  private def initialState =
    LWWRegister(
      selfUniqueAddress,
      ScaleState(
        revision = 0,
        numberOfProcesses = initialNumberOfProcesses,
        completed = true,
        // not quite correct but also not important
        started = started))

  def start(): Behavior[ShardedDaemonProcessCommand] = {
    replicatorAdapter.askGet(
      replyTo => Replicator.Get(key, Replicator.ReadMajority(readTimeout), replyTo),
      response => InternalGetResponse(response))

    Behaviors.receiveMessagePartial {
      case InternalGetResponse(success @ Replicator.GetSuccess(`key`)) =>
        val existingScaleState = success.get(key).value
        if (existingScaleState.completed) {
          context.log.debugN(
            "{}: Previous completed state found for Sharded Daemon Process rev [{}] from [{}]",
            daemonProcessName,
            existingScaleState.revision,
            existingScaleState.started)
          idle(existingScaleState)
        } else {
          context.log.debugN(
            "{}: Previous non completed state found for Sharded Daemon Process rev [{}] from [{}], retriggering scaling",
            daemonProcessName,
            existingScaleState.revision,
            existingScaleState.started)
          pauseAllPingers(existingScaleState, None, initialNumberOfProcesses)
        }

      case InternalGetResponse(_ @Replicator.NotFound(`key`)) =>
        context.log.debug("{}: No previous state stored for Sharded Daemon Process", daemonProcessName)
        idle(initialState.value)

      case InternalGetResponse(_ @Replicator.GetFailure(`key`)) =>
        context.log.info("{}: Failed fetching initial state for Sharded Daemon Process, retrying", daemonProcessName)
        start()

    }
  }

  private def idle(currentState: ScaleState): Behavior[ShardedDaemonProcessCommand] = {

    Behaviors.receiveMessagePartial {
      case request: ChangeNumberOfProcesses =>
        if (request.newNumberOfProcesses == currentState.numberOfProcesses) {
          context.log.info(
            "{}: Rescale command for Sharded Daemon Process ignored, already running [{}] processes",
            daemonProcessName,
            request.newNumberOfProcesses)
          request.replyTo ! StatusReply.Ack
          Behaviors.same
        } else {
          val newState =
            ScaleState(
              currentState.revision + 1,
              request.newNumberOfProcesses,
              completed = false,
              started = Instant.now())
          context.log.infoN(
            "{}: Starting rescaling of Sharded Daemon Process to [{}] processes, rev [{}]",
            daemonProcessName,
            request.newNumberOfProcesses,
            newState.revision)
          prepareRescale(newState, request, currentState.numberOfProcesses)
        }
    }
  }

  private def prepareRescale(
      newState: ShardedDaemonProcessCoordinator.ScaleState,
      request: ChangeNumberOfProcesses,
      previousNumberOfProcesses: Int): Behavior[ShardedDaemonProcessCommand] = {
    replicatorAdapter.askUpdate(
      replyTo =>
        Replicator.Update(key, initialState, Replicator.WriteMajority(writeTimeout), replyTo)((register: Register) =>
          register.withValue(selfUniqueAddress, newState)),
      response => InternalUpdateResponse(response))

    // wait for update to complete before applying
    receiveWhileRescaling("prepareRescale") {
      case InternalUpdateResponse(_ @Replicator.UpdateSuccess(`key`)) =>
        pauseAllPingers(newState, Some(request), previousNumberOfProcesses)

      case InternalUpdateResponse(_ @Replicator.UpdateTimeout(`key`)) =>
        // retry
        // FIXME should we count retries and eventually give up?
        context.log.debug("{}: Failed updating scale state for sharded daemon process, retrying", daemonProcessName)
        prepareRescale(newState, request, previousNumberOfProcesses)
    }
  }

  // first step of rescale, pause all pinger actors so they do not wake the entities up again
  // when we stop them
  private def pauseAllPingers(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      previousNumberOfProcesses: Int): Behavior[ShardedDaemonProcessCommand] = {
    context.log.debug2("{}: Pausing all pingers (with revision [{}])", daemonProcessName, state.revision - 1)
    timers.startTimerWithFixedDelay(PauseAllKeepalivePingers, pingerRetryTimeout)
    pingersTopic ! Topic.Publish(Pause(state.revision - 1, pingerResponseAdapter))
    waitForAllPingersPaused(state, request, previousNumberOfProcesses, Set.empty)
  }

  private def waitForAllPingersPaused(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      previousNumberOfProcesses: Int,
      seenAcks: Set[Address]): Behavior[ShardedDaemonProcessCommand] = {
    receiveWhileRescaling("waitForAllPingersPaused") {
      case PauseAllKeepalivePingers =>
        context.log.debugN(
          "{}: Did not see acks from all pingers paused within [{}], retrying pause (seen acks [{}])",
          daemonProcessName,
          pingerRetryTimeout,
          seenAcks.mkString(", "))
        pingersTopic ! Topic.Publish(Pause(state.revision - 1, pingerResponseAdapter))
        Behaviors.same
      case PingerAck(path) =>
        val newSeenAcks = seenAcks + (if (path.address.hasLocalScope) cluster.selfMember.address else path.address)
        if (newSeenAcks.subsetOf(nodesWithKeepalivePingers())) {
          context.log.debug("{}: All pingers paused, stopping shards", daemonProcessName)
          timers.cancel(PauseAllKeepalivePingers)
          stopAllShards(state, request, previousNumberOfProcesses)
        } else waitForAllPingersPaused(state, request, previousNumberOfProcesses, newSeenAcks)
      case PingerNack(ex) =>
        context.log.debug2(
          "{}: Failed pausing all pingers, retrying pause of all pingers (error message {})",
          daemonProcessName,
          ex.getMessage)
        timers.cancel(PauseAllKeepalivePingers)
        pauseAllPingers(state, request, previousNumberOfProcesses)
    }
  }

  private def stopAllShards(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      previousNumberOfProcesses: Int): Behavior[ShardedDaemonProcessCommand] = {
    val allShards = ShardedDaemonProcessImpl.allShardsFor(state.revision - 1, previousNumberOfProcesses)
    shardingRef.tell(ShardCoordinator.Internal.StopShards(allShards), shardStoppedAdapter)

    timers.startSingleTimer(ShardStopTimeout, stopShardsTimeout)
    waitForShardsStopping(state, request, previousNumberOfProcesses, allShards)
  }

  private def waitForShardsStopping(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      previousNumberOfProcesses: Int,
      shardsStillRunning: Set[String]): Behavior[ShardedDaemonProcessCommand] = {

    receiveWhileRescaling("waitForShardsStopping") {
      case ShardStopped(shard) =>
        val newShardsStillRunning = shardsStillRunning - shard
        if (newShardsStillRunning.isEmpty) {
          timers.cancel(ShardStopTimeout)
          restartAllPingers(state, request)
        } else waitForShardsStopping(state, request, previousNumberOfProcesses, newShardsStillRunning)
      case ShardStopTimeout =>
        context.log.debug2("{}: Stopping shards timed out after [{}] retrying", daemonProcessName, stopShardsTimeout)
        stopAllShards(state, request, previousNumberOfProcesses)
    }
  }

  private def restartAllPingers(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    context.log.debug2("{}: Restarting all pingers (with revision [{}])", daemonProcessName, state.revision)
    timers.startTimerWithFixedDelay(RestartAllKeepalivePingers, pingerRetryTimeout)
    pingersTopic ! Topic.Publish(Restart(state.revision, state.numberOfProcesses, pingerResponseAdapter))
    waitForAllPingersStarted(state, request, Set.empty)
  }

  private def waitForAllPingersStarted(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      restartedPingers: Set[Address]): Behavior[ShardedDaemonProcessCommand] = {
    receiveWhileRescaling("waitForAllPingersStarted") {
      case RestartAllKeepalivePingers =>
        context.log.debug2(
          "{}: Did not see acks from all pingers restarted within [{}], retrying restart",
          daemonProcessName,
          pingerRetryTimeout)
        pingersTopic ! Topic.Publish(Restart(state.revision, state.numberOfProcesses, pingerResponseAdapter))
        Behaviors.same
      case PingerAck(path) =>
        context.log.debug("{}: All pingers restarted, completing rescale", daemonProcessName)
        val newRestartedPingers = restartedPingers + (if (path.address.hasLocalScope) cluster.selfMember.address
                                                      else path.address)
        if (newRestartedPingers.subsetOf(nodesWithKeepalivePingers())) {
          timers.cancel(PauseAllKeepalivePingers)
          rescalingComplete(state, request)
        } else waitForAllPingersStarted(state, request, newRestartedPingers)
      case PingerNack(ex) =>
        context.log.debug2(
          "{}: Failed restarting all pingers, retrying pause of all pingers (error message {})",
          daemonProcessName,
          ex.getMessage)
        timers.cancel(PauseAllKeepalivePingers)
        restartAllPingers(state, request)
    }
  }

  private def rescalingComplete(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      ddataWritRetries: Int = 0): Behavior[ShardedDaemonProcessCommand] = {
    request.foreach(req => req.replyTo ! StatusReply.Ack)
    val newState = state.copy(completed = true)
    replicatorAdapter.askUpdate(
      replyTo =>
        Replicator.Update(key, initialState, Replicator.WriteMajority(writeTimeout), replyTo)((register: Register) =>
          register.withValue(selfUniqueAddress, newState)),
      response => InternalUpdateResponse(response))

    receiveWhileRescaling("rescalingComplete") {
      case InternalUpdateResponse(_ @Replicator.UpdateSuccess(`key`)) =>
        idle(newState)

      case InternalUpdateResponse(_ @Replicator.UpdateTimeout(`key`)) =>
        // retry
        if (ddataWritRetries < 5) {
          context.log.debug(
            "{}: Failed updating scale state for sharded daemon process after completing rescale, retrying",
            daemonProcessName)
        } else {
          context.log.warn(
            "{}: Failed updating scale state for sharded daemon process after completing rescale, retry [{}]",
            daemonProcessName,
            ddataWritRetries)
        }
        // FIXME should we eventually give up?

        rescalingComplete(newState, None, ddataWritRetries + 1)
    }
  }

  private def receiveWhileRescaling(stepName: String)(
      pf: PartialFunction[ShardedDaemonProcessCommand, Behavior[ShardedDaemonProcessCommand]])
      : Behavior[ShardedDaemonProcessCommand] =
    Behaviors.receiveMessage(pf.orElse {
      case request: ChangeNumberOfProcesses =>
        request.replyTo ! StatusReply.error("Rescale in progress, retry later")
        Behaviors.same
      case msg =>
        context.log.debugN("{}: Unexpected message in step {}: {}", daemonProcessName, stepName, msg.getClass)
        Behaviors.same
    })

  private def nodesWithKeepalivePingers(): Set[Address] =
    (settings.role match {
      case Some(role) => cluster.state.members.filter(_.hasRole(role))
      case None       => cluster.state.members
    }).map(_.address)
}
