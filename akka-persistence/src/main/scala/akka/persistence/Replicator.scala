/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._

import akka.actor._
import akka.persistence.JournalProtocol._

/**
 * Instructs a replicator to run a replication (i.e. an incremental message replay).
 *
 * @param awaitReplication if `true` processing of further messages will be delayed until
 *                         replication completes. If `false`, any following message may
 *                         interleave with the replayed [[Persistent]] message stream.
 */
case class Replicate(awaitReplication: Boolean)

/**
 * A replicator replicates the persistent message stream of a corresponding processor. Messages written
 * by a processor are automatically received by all replicators with a matching `processorId`. There is
 * no direct communication between a processor its replicators. Instead, replicators execute incremental
 * [[Persistent]] message replays by interacting with the journal. Replicators update themselves using a
 * pull-based approach.
 *
 * Replication runs periodically with an interval that can be configured via the
 * `akka.persistence.replicator.default-replication-interval` key or by overriding `replicationInterval`.
 * Overriding the `replicationInterval` method takes precedence. An incremental replication can also be
 * triggered by applications by sending a replicator a [[Replicate]] message.
 *
 * Replicators can be used to generate (eventual consistent) views of their corresponding processor's
 * state (the R in CQRS). These views can also run on different nodes, provided that a replicated journal
 * is used.
 *
 * Replicators can also store snapshots of internal state by calling [[saveSnapshot]]. During recovery,
 * a saved snapshot is offered to the replicator with a [[SnapshotOffer]] message, followed by replayed
 * messages, if any, that are younger than the snapshot. Default is to offer the latest saved snapshot.
 */
trait Replicator extends Actor with Recovery {
  import context.dispatcher

  /**
   * INTERNAL API.
   *
   * Extends the `recoveryStarted` state of [[Recovery]] with logic to handle [[Replicate]] requests sent
   * by users.
   */
  private[persistence] override def recoveryStarted(awaitReplay: Boolean) = new State {
    private var fallback = Replicator.super.recoveryStarted(awaitReplay)

    override def aroundReceive(receive: Receive, message: Any) = message match {
      case Replicate(awaitReplication) ⇒ if (!awaitReplay && awaitReplication)
        fallback = Replicator.super.recoveryStarted(awaitReplay = true)
      case other ⇒
        fallback.aroundReceive(receive, other)
    }
  }

  /**
   * Schedules a [[Replicate]] message when this state is entered. Runs an incremental replay if [[Replicate]]
   * is received, otherwise invokes the actor's behavior with any other received message.
   */
  private def idle: State = new State {
    override def toString: String = "idle"

    schedule = Some(context.system.scheduler.scheduleOnce(replicationInterval, self, Replicate(awaitReplication = false)))

    def aroundReceive(receive: Receive, message: Any): Unit = message match {
      case Replicate(awaitReplication) ⇒
        _currentState = recoveryStarted(awaitReplay = awaitReplication)
        journal ! Replay(lastSequenceNr + 1L, Long.MaxValue, processorId, self)
      case r: Recover ⇒ // ignore
      case other      ⇒ process(receive, other)
    }
  }

  /**
   * INTERNAL API.
   */
  private[persistence] def onReplaySuccess(receive: Receive, await: Boolean, maxSnr: Long): Unit =
    onReplayComplete(await)

  /**
   * INTERNAL API.
   */
  private[persistence] def onReplayFailure(receive: Receive, await: Boolean, cause: Throwable): Unit =
    onReplayComplete(await)

  private val defaultReplicationInterval = FiniteDuration(configuredReplicationInterval, MILLISECONDS)
  private val _replicatorId = extension.processorId(self)

  private var schedule: Option[Cancellable] = None

  /**
   * Replicator id. Defaults to this replicator's path and can be overridden.
   */
  def replicatorId: String = _replicatorId

  /**
   * Returns `replicatorId`.
   */
  def snapshotterId: String = replicatorId

  /**
   * This replicator's replication interval. It defaults to 5 seconds and can be changed by
   * configuration via the `akka.persistence.replicator.default-replication-interval` key or
   * overriding this method. Overriding takes precedence over configuration.
   */
  def replicationInterval: FiniteDuration =
    defaultReplicationInterval

  private def configuredReplicationInterval: Long =
    context.system.settings.config.getMilliseconds("akka.persistence.replicator.default-replication-interval")

  private def onReplayComplete(await: Boolean): Unit = {
    _currentState = idle
    if (await) receiverStash.unstashAll()
  }

  override def preStart(): Unit = {
    super.preStart()
    self ! Recover()
  }

  override def postStop(): Unit = {
    schedule.foreach(_.cancel())
    super.postStop()
  }
}
