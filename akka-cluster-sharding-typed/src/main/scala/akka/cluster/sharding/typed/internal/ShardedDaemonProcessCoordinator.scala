/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.Done
import akka.actor.ActorPath
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.annotation.InternalApi
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.ddata.typed.scaladsl.Replicator
import akka.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.sharding.typed.ChangeNumberOfProcesses
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ShardedDaemonProcessCommand
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.StartEntity
import akka.pattern.StatusReply
import akka.stream.scaladsl.Source

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 *
 * A ShardedDaemonProcessCoordinator manages dynamic scaling of one setup of ShardedDaemonProcess
 */
@InternalApi
private[akka] object ShardedDaemonProcessCoordinator {
  import ShardedDaemonProcessState._

  private trait InternalMessage extends ShardedDaemonProcessCommand

  private case class InternalGetResponse(rsp: Replicator.GetResponse[Register]) extends InternalMessage

  private case class InternalUpdateResponse(rsp: Replicator.UpdateResponse[Register]) extends InternalMessage

  private case class PingerPauseAck(actorPath: ActorPath) extends InternalMessage
  private case class PingerResumeAck(actorPath: ActorPath) extends InternalMessage
  private case class PingerNack(ex: Throwable) extends InternalMessage

  private case object PauseAllKeepalivePingers extends InternalMessage
  private case object RestartAllKeepalivePingers extends InternalMessage
  private case class ShardStopped(shard: String) extends InternalMessage

  private case object Tick extends InternalMessage
  private case object SendKeepAliveDone extends InternalMessage

  private object ShardStopTimeout extends InternalMessage

  def apply[T](
      settings: ShardedDaemonProcessSettings,
      shardingSettings: ClusterShardingSettings,
      supportsRescale: Boolean,
      initialNumberOfProcesses: Int,
      daemonProcessName: String,
      shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[ShardedDaemonProcessCommand] = {
    Behaviors
      .supervise[ShardedDaemonProcessCommand](Behaviors.setup { context =>
        Behaviors.withTimers {
          timers =>
            Behaviors.withStash(100) {
              stash =>
                context.log.debug("ShardedDaemonProcessCoordinator for [{}] starting", daemonProcessName)
                val key = ddataKey(daemonProcessName)
                val ddata = DistributedData(context.system)
                val selfUniqueAddress = ddata.selfUniqueAddress

                DistributedData.withReplicatorMessageAdapter[ShardedDaemonProcessCommand, Register] {
                  replicatorAdapter =>
                    new ShardedDaemonProcessCoordinator(
                      settings,
                      shardingSettings,
                      supportsRescale,
                      context,
                      timers,
                      stash,
                      daemonProcessName,
                      shardingRef.toClassic,
                      initialNumberOfProcesses,
                      key,
                      selfUniqueAddress,
                      replicatorAdapter).start()
                }
            }
        }
      })
      .onFailure(SupervisorStrategy.restart)
  }
}

/**
 * Rescaling workflow overview:
 *
 * 1. stop pinging workers
 * 2. Write to ddata new size and revision, in progress/not complete
 * 3. Stop shards for all workers via shard coordinator
 * 4. Update ddata state, mark rescaling completed
 * 5. start pinging workers again
 *
 * If coordinator moved before completing it will re-trigger workflow from step 2 on start, worst case stopping all
 * workers before starting them again.
 */
private final class ShardedDaemonProcessCoordinator private (
    settings: ShardedDaemonProcessSettings,
    shardingSettings: ClusterShardingSettings,
    supportsRescale: Boolean,
    context: ActorContext[ShardedDaemonProcessCommand],
    timers: TimerScheduler[ShardedDaemonProcessCommand],
    stash: StashBuffer[ShardedDaemonProcessCommand],
    daemonProcessName: String,
    shardingRef: akka.actor.ActorRef,
    initialNumberOfProcesses: Int,
    key: LWWRegisterKey[ShardedDaemonProcessState],
    selfUniqueAddress: SelfUniqueAddress,
    replicatorAdapter: ReplicatorMessageAdapter[ShardedDaemonProcessCommand, ShardedDaemonProcessState.Register]) {
  import ShardedDaemonProcessCoordinator._
  import ShardedDaemonProcessState._
  private val started = Instant.now()

  // Stopping shards can take a lot of time, so don't hammer it with retries (even if the
  // stop shard command is idempotent)
  private val stopShardsTimeout = shardingSettings.tuningParameters.handOffTimeout / 4

  private val shardStoppedAdapter = context
    .messageAdapter[ShardCoordinator.Internal.ShardStopped] {
      case ShardCoordinator.Internal.ShardStopped(shard) => ShardStopped(shard)
    }
    .toClassic

  private def initialState =
    LWWRegister(
      selfUniqueAddress,
      ShardedDaemonProcessState(
        revision = ShardedDaemonProcessState.startRevision,
        numberOfProcesses = initialNumberOfProcesses,
        completed = true,
        // not quite correct but also not important
        started = started))

  def start(): Behavior[ShardedDaemonProcessCommand] = {
    replicatorAdapter.askGet(
      replyTo => Replicator.Get(key, Replicator.ReadMajority(settings.rescaleReadStateTimeout), replyTo),
      response => InternalGetResponse(response))

    Behaviors.receiveMessage {
      case InternalGetResponse(success @ Replicator.GetSuccess(`key`)) =>
        val existingScaleState = success.get(key).value
        if (existingScaleState.completed) {
          context.log.debugN(
            "{}: Previous completed state found for Sharded Daemon Process rev [{}] from [{}]",
            daemonProcessName,
            existingScaleState.revision,
            existingScaleState.started)
          stash.stash(Tick)
          stash.unstashAll(idle(existingScaleState))
        } else {
          context.log.debugN(
            "{}: Previous non completed state found for Sharded Daemon Process rev [{}] from [{}], retriggering scaling",
            daemonProcessName,
            existingScaleState.revision,
            existingScaleState.started)
          stopAllShards(existingScaleState, None, initialNumberOfProcesses)
        }

      case InternalGetResponse(_ @Replicator.NotFound(`key`)) =>
        context.log.debug("{}: No previous state stored for Sharded Daemon Process", daemonProcessName)
        stash.stash(Tick)
        stash.unstashAll(idle(initialState.value))

      case InternalGetResponse(_ @Replicator.GetFailure(`key`)) =>
        context.log.info("{}: Failed fetching initial state for Sharded Daemon Process, retrying", daemonProcessName)
        start()

      case other =>
        stash.stash(other)
        Behaviors.same
    }
  }

  private def idle(currentState: ShardedDaemonProcessState): Behavior[ShardedDaemonProcessCommand] = {

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
            ShardedDaemonProcessState(
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

      case Tick =>
        val sortedIdentities =
          ShardedDaemonProcessId.sortedIdentitiesFor(
            currentState.revision,
            currentState.numberOfProcesses,
            supportsRescale)
        context.log.debugN(
          "Sending periodic keep alive for Sharded Daemon Process [{}] to [{}] processes (revision [{}]).",
          daemonProcessName,
          sortedIdentities.size,
          currentState.revision)
        context.pipeToSelf(sendKeepAliveMessages(sortedIdentities))(_ => SendKeepAliveDone)
        Behaviors.same

      case SendKeepAliveDone =>
        timers.startSingleTimer(Tick, settings.keepAliveInterval)
        Behaviors.same

    }
  }

  private def prepareRescale(
      newState: ShardedDaemonProcessState,
      request: ChangeNumberOfProcesses,
      previousNumberOfProcesses: Int,
      updateRetry: Int = 0): Behavior[ShardedDaemonProcessCommand] = {
    replicatorAdapter.askUpdate(
      replyTo =>
        Replicator.Update(key, initialState, Replicator.WriteMajority(settings.rescaleWriteStateTimeout), replyTo)(
          (register: Register) => register.withValue(selfUniqueAddress, newState)),
      response => InternalUpdateResponse(response))

    // wait for update to complete before applying
    receiveWhileRescaling("prepareRescale") {
      case InternalUpdateResponse(_ @Replicator.UpdateSuccess(`key`)) =>
        stopAllShards(newState, Some(request), previousNumberOfProcesses)

      case InternalUpdateResponse(_ @Replicator.UpdateTimeout(`key`)) =>
        // retry
        if (updateRetry > 5) {
          context.log.warn2(
            "{}: Failed updating scale state for sharded daemon process, retrying, retry [{}]",
            daemonProcessName,
            updateRetry)
        } else {
          context.log.debug("{}: Failed updating scale state for sharded daemon process, retrying", daemonProcessName)
        }
        prepareRescale(newState, request, previousNumberOfProcesses, updateRetry + 1)
    }
  }

  private def stopAllShards(
      state: ShardedDaemonProcessState,
      request: Option[ChangeNumberOfProcesses],
      previousNumberOfProcesses: Int): Behavior[ShardedDaemonProcessCommand] = {
    val allShards =
      ShardedDaemonProcessId.allShardsFor(state.revision - 1, previousNumberOfProcesses, supportsRescale = true)
    shardingRef.tell(ShardCoordinator.Internal.StopShards(allShards), shardStoppedAdapter)

    timers.startSingleTimer(ShardStopTimeout, stopShardsTimeout)
    waitForShardsStopping(state, request, previousNumberOfProcesses, allShards)
  }

  private def waitForShardsStopping(
      state: ShardedDaemonProcessState,
      request: Option[ChangeNumberOfProcesses],
      previousNumberOfProcesses: Int,
      shardsStillRunning: Set[String]): Behavior[ShardedDaemonProcessCommand] = {

    receiveWhileRescaling("waitForShardsStopping") {
      case ShardStopped(shard) =>
        val newShardsStillRunning = shardsStillRunning - shard
        if (newShardsStillRunning.isEmpty) {
          timers.cancel(ShardStopTimeout)
          rescalingComplete(state, request)
        } else waitForShardsStopping(state, request, previousNumberOfProcesses, newShardsStillRunning)
      case ShardStopTimeout =>
        context.log.debug2("{}: Stopping shards timed out after [{}] retrying", daemonProcessName, stopShardsTimeout)
        stopAllShards(state, request, previousNumberOfProcesses)
    }
  }

  private def rescalingComplete(
      state: ShardedDaemonProcessState,
      request: Option[ChangeNumberOfProcesses],
      ddataWritRetries: Int = 0): Behavior[ShardedDaemonProcessCommand] = {
    request.foreach(req => req.replyTo ! StatusReply.Ack)
    val newState = state.copy(completed = true)
    replicatorAdapter.askUpdate(
      replyTo =>
        Replicator.Update(key, initialState, Replicator.WriteMajority(settings.rescaleWriteStateTimeout), replyTo)(
          (register: Register) => register.withValue(selfUniqueAddress, newState)),
      response => InternalUpdateResponse(response))

    receiveWhileRescaling("rescalingComplete") {
      case InternalUpdateResponse(_ @Replicator.UpdateSuccess(`key`)) =>
        context.self ! Tick
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
        context.log.debugN("{}: Unexpected message in step {}: {}", daemonProcessName, stepName, msg.getClass.getName)
        Behaviors.same
    })

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
