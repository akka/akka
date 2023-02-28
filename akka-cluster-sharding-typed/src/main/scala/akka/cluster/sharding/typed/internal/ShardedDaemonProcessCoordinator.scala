/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.actor.ActorPath
import akka.actor.typed.Behavior
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.TimerScheduler
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import akka.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter
import akka.cluster.sharding.typed.ChangeNumberOfProcesses
import akka.cluster.sharding.typed.ShardedDaemonProcessCommand
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
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

  private case class PingerPauseAck(actorPath: ActorPath) extends InternalMessage
  private case class PingerRestartAck(actorPath: ActorPath) extends InternalMessage

  private case object PauseAllKeepalivePingers extends InternalMessage
  private case object RestartAllKeepalivePingers extends InternalMessage

  def apply(
      settings: ShardedDaemonProcessSettings,
      initialNumberOfProcesses: Int,
      daemonProcessName: String): Behavior[ShardedDaemonProcessCommand] =
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
    initialNumberOfProcesses: Int,
    key: LWWRegisterKey[ShardedDaemonProcessCoordinator.ScaleState],
    selfUniqueAddress: SelfUniqueAddress,
    replicatorAdapter: ReplicatorMessageAdapter[ShardedDaemonProcessCommand, ShardedDaemonProcessCoordinator.Register]) {
  import ShardedDaemonProcessCoordinator._
  private val started = Instant.now()
  // FIXME write timeouts from config
  private val writeTimeout = 5.seconds
  private val readTimeout = 5.seconds
  private val pingerRetryTimeout = 3.seconds

  private val cluster = Cluster(context.system)

  // topic so we can reach pingers
  val pingersTopic = context.spawn(ShardedDaemonProcessKeepAlivePinger.topicFor(daemonProcessName), "topic")

  val pauseAdapter = context.messageAdapter[StatusReply[ActorPath]] {
    case StatusReply.Success(path: ActorPath) => PingerPauseAck(path)
    case _                                    => ??? // FIXME should we skip the status and just not respond on error?
  }

  val restartAdapter = context.messageAdapter[StatusReply[ActorPath]] {
    case StatusReply.Success(path: ActorPath) => PingerRestartAck(path)
    case _                                    => ??? // FIXME should we skip the status and just not respond on error?
  }

  private def initialState =
    LWWRegister(
      selfUniqueAddress,
      ScaleState(
        revision = 0,
        numberOfProcesses = initialNumberOfProcesses,
        completed = true,
        // not quite correct but also not important
        started = started))

  // FIXME stash or deny new requests if rescale in progress?
  def start(): Behavior[ShardedDaemonProcessCommand] = {
    replicatorAdapter.askGet(
      replyTo => Replicator.Get(key, Replicator.ReadMajority(readTimeout), replyTo),
      response => InternalGetResponse(response))

    Behaviors.receiveMessagePartial {
      case InternalGetResponse(success @ Replicator.GetSuccess(`key`)) =>
        val existingScaleState = success.get(key).value
        if (existingScaleState.completed) {
          context.log.debugN(
            "Previous completed state found for Sharded Daemon Process [{}] rev [{}] from [{}]",
            daemonProcessName,
            existingScaleState.revision,
            existingScaleState.started)
          idle(existingScaleState)
        } else {
          context.log.debugN(
            "Previous non completed state found for Sharded Daemon Process [{}] rev [{}] from [{}], retriggering scaling",
            daemonProcessName,
            existingScaleState.revision,
            existingScaleState.started)
          pauseAllPingers(existingScaleState, None)
        }

      case InternalGetResponse(_ @Replicator.NotFound(`key`)) =>
        context.log.debug("No previous state stored for Sharded Daemon Process [{}]", daemonProcessName)
        idle(initialState.value)

      case InternalGetResponse(_ @Replicator.GetFailure(`key`)) =>
        context.log.info("Failed fetching initial state for Sharded Daemon Process [{}], retrying", daemonProcessName)
        start()

    }
  }

  private def idle(currentState: ScaleState): Behavior[ShardedDaemonProcessCommand] = {

    Behaviors.receiveMessagePartial {
      case request: ChangeNumberOfProcesses =>
        if (request.newNumberOfProcesses == currentState.numberOfProcesses) {
          context.log.info(
            "Rescale command for Sharded Daemon Process [{}] ignored, already running [{}] processes",
            request.newNumberOfProcesses)
          Behaviors.same
        } else {
          val newState =
            ScaleState(
              currentState.revision + 1,
              request.newNumberOfProcesses,
              completed = false,
              started = Instant.now())
          context.log.infoN(
            "Starting rescaling of Sharded Daemon Process [{}] to [{}] processes, rev [{}]",
            daemonProcessName,
            request.newNumberOfProcesses,
            newState.revision)
          prepareRescale(newState, request)
        }
    }
  }

  private def prepareRescale(
      newState: ShardedDaemonProcessCoordinator.ScaleState,
      request: ChangeNumberOfProcesses): Behavior[ShardedDaemonProcessCommand] = {
    replicatorAdapter.askUpdate(
      replyTo =>
        Replicator.Update(key, initialState, Replicator.WriteMajority(writeTimeout), replyTo)((register: Register) =>
          register.withValue(selfUniqueAddress, newState)),
      response => InternalUpdateResponse(response))

    // wait for update to complete before applying
    Behaviors.receiveMessagePartial {
      case InternalUpdateResponse(_ @Replicator.UpdateSuccess(`key`)) =>
        pauseAllPingers(newState, Some(request))

      case InternalUpdateResponse(_ @Replicator.UpdateTimeout(`key`)) =>
        // retry
        // FIXME should we count retries and eventually give up?
        context.log.info("Failed updating scale state for sharded daemon process [{}], retrying", daemonProcessName)
        prepareRescale(newState, request)
    }
  }

  // first step of rescale, pause all pinger actors so they do not wake the entities up again
  // when we stop them
  private def pauseAllPingers(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    context.log.debug("Pausing all pingers (with revision [{}])", state.revision - 1)
    timers.startTimerWithFixedDelay(PauseAllKeepalivePingers, pingerRetryTimeout)
    pingersTopic ! Topic.Publish(Pause(state.revision - 1, pauseAdapter))
    waitForAllPingersPaused(state, request, Set.empty)
  }

  private def waitForAllPingersPaused(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      seenAcks: Set[ActorPath]): Behavior[ShardedDaemonProcessCommand] = {
    Behaviors.receiveMessagePartial {
      case PauseAllKeepalivePingers =>
        context.log.debug("Did not see acks from all pingers paused within [{}], retrying pause", pingerRetryTimeout)
        pingersTopic ! Topic.Publish(Pause(state.revision - 1, pauseAdapter))
        Behaviors.same
      case PingerPauseAck(path) =>
        val newSeenAcks = seenAcks + path
        if (newSeenAcks.size == numberOfNodesWithKeepalivePingers()) { // FIXME number is not enough if topology changed
          context.log.debug("All pingers paused, stopping shards")
          timers.cancel(PauseAllKeepalivePingers)
          stopAllShards(state, request)
        } else waitForAllPingersPaused(state, request, newSeenAcks)
    }
  }

  private def stopAllShards(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    // FIXME add support in sharding for stop with ack
    // once sharding stopped
    restartAllPingers(state, request)
  }

  private def restartAllPingers(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    context.log.debug("Restarting all pingers (with revision [{}]", state.revision)
    timers.startTimerWithFixedDelay(PauseAllKeepalivePingers, pingerRetryTimeout)
    pingersTopic ! Topic.Publish(Restart(state.revision, restartAdapter))
    waitForAllPingersStarted(state, request, Set.empty)
  }

  private def waitForAllPingersStarted(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses],
      restartedPingers: Set[ActorPath]): Behavior[ShardedDaemonProcessCommand] = {
    Behaviors.receiveMessagePartial {
      case RestartAllKeepalivePingers =>
        context.log
          .debug("Did not see acks from all pingers restarted within [{}], retrying restart", pingerRetryTimeout)
        pingersTopic ! Topic.Publish(Restart(state.revision, restartAdapter))
        Behaviors.same
      case PingerPauseAck(path) =>
        context.log.debug("All pingers restarted, completing rescale")
        val newRestartedPingers = restartedPingers + path
        if (newRestartedPingers.size == numberOfNodesWithKeepalivePingers()) { // FIXME number is not enough if topology changed
          timers.cancel(PauseAllKeepalivePingers)
          rescalingComplete(state, request)
        } else waitForAllPingersPaused(state, request, newRestartedPingers)
    }
  }

  private def rescalingComplete(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    request.foreach(req => req.replyTo ! StatusReply.Ack)
    val newState = state.copy(completed = true)
    replicatorAdapter.askUpdate(
      replyTo =>
        Replicator.Update(key, initialState, Replicator.WriteMajority(writeTimeout), replyTo)((register: Register) =>
          register.withValue(selfUniqueAddress, newState)),
      response => InternalUpdateResponse(response))

    Behaviors.receiveMessagePartial {
      case InternalUpdateResponse(_ @Replicator.UpdateSuccess(`key`)) =>
        idle(newState)

      case InternalUpdateResponse(_ @Replicator.UpdateTimeout(`key`)) =>
        // retry
        // FIXME should we count retries and eventually give up?
        context.log.info(
          "Failed updating scale state for sharded daemon process after completing rescale [{}], retrying",
          daemonProcessName)
        rescalingComplete(newState, None)
    }
  }

  private def numberOfNodesWithKeepalivePingers(): Int =
    settings.role match {
      case Some(role) => cluster.state.members.count(_.hasRole(role))
      case None       => cluster.state.members.size
    }

}
