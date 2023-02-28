/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.Routers
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import akka.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter
import akka.cluster.sharding.typed.ChangeNumberOfProcesses
import akka.cluster.sharding.typed.ShardedDaemonProcessCommand
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
  private def keyFor(name: String) = LWWRegisterKey[ScaleState](name)
  private trait InternalCommand extends ShardedDaemonProcessCommand

//   private case class InternalSubscribeResponse(chg: Replicator.SubscribeResponse[Register]) extends InternalCommand

  private case class InternalGetResponse(rsp: Replicator.GetResponse[Register]) extends InternalCommand

  private case class InternalUpdateResponse(rsp: Replicator.UpdateResponse[Register]) extends InternalCommand

  def apply(initialNumberOfProcesses: Int, daemonProcessName: String): Behavior[ShardedDaemonProcessCommand] =
    Behaviors.setup { context =>
      context.log.debug("ShardedDaemonProcessCoordinator for [{}] starting", daemonProcessName)
      val key = keyFor(daemonProcessName)
      val ddata = DistributedData(context.system)
      val selfUniqueAddress = ddata.selfUniqueAddress

      DistributedData.withReplicatorMessageAdapter[ShardedDaemonProcessCommand, Register] { replicatorAdapter =>
        new ShardedDaemonProcessCoordinator(
          context,
          daemonProcessName,
          initialNumberOfProcesses,
          key,
          selfUniqueAddress,
          replicatorAdapter).start()
      }
    }

}

private class ShardedDaemonProcessCoordinator(
    context: ActorContext[ShardedDaemonProcessCommand],
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

  // FIXME we don't have broadcast support, use topic instead
  val keepAlivePingers = Routers.group(ShardedDaemonProcessKeepAlivePinger.serviceKeyFor(daemonProcessName))

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
          startRescaling(existingScaleState, None)
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
        startRescaling(newState, Some(request))

      case InternalUpdateResponse(_ @Replicator.UpdateTimeout(`key`)) =>
        // retry
        // FIXME should we count retries and eventually give up?
        context.log.info("Failed updating scale state for sharded daemon process [{}], retrying", daemonProcessName)
        prepareRescale(newState, request)
    }
  }

  private def startRescaling(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    // FIXME delay to let pingers have time to see the updated revision before shutting down shards? or coordinate somehow?
    // once pingers stopped ->
    pauseAllPingers(state, request)
    ???
  }

  private def pauseAllPingers(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    stopAllShards(state, request)
    ???
  }

  private def stopAllShards(
      state: ShardedDaemonProcessCoordinator.ScaleState,
      request: Option[ChangeNumberOfProcesses]): Behavior[ShardedDaemonProcessCommand] = {
    // FIXME add support in sharding
    // once sharding stopped
    rescalingComplete(state, request)
    ???
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
}
