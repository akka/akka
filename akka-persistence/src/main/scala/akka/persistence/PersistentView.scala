/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.actor.AbstractActor
import akka.actor.Actor
import akka.actor.ActorKilledException
import akka.actor.Cancellable
import akka.actor.Stash
import akka.actor.StashFactory
import akka.actor.UntypedActor
import akka.dispatch.Envelope

/**
 * Instructs a [[PersistentView]] to update itself. This will run a single incremental message replay with
 * all messages from the corresponding persistent id's journal that have not yet been consumed by the view.
 * To update a view with messages that have been written after handling this request, another `Update`
 * request must be sent to the view.
 *
 * @param await if `true`, processing of further messages sent to the view will be delayed until the
 *              incremental message replay, triggered by this update request, completes. If `false`,
 *              any message sent to the view may interleave with replayed persistent event stream.
 * @param replayMax maximum number of messages to replay when handling this update request. Defaults
 *                  to `Long.MaxValue` (i.e. no limit).
 */
@SerialVersionUID(1L)
final case class Update(await: Boolean = false, replayMax: Long = Long.MaxValue)

object Update {
  /**
   * Java API.
   */
  def create() =
    Update()

  /**
   * Java API.
   */
  def create(await: Boolean) =
    Update(await)

  /**
   * Java API.
   */
  def create(await: Boolean, replayMax: Long) =
    Update(await, replayMax)
}

/**
 * INTERNAL API
 */
private[akka] object PersistentView {
  private final case class ScheduledUpdate(replayMax: Long)
}

/**
 * A view replicates the persistent message stream of a [[PersistentActor]]. Implementation classes receive
 * the message stream directly from the Journal. These messages can be processed to update internal state
 * in order to maintain an (eventual consistent) view of the state of the corresponding persistent actor. A
 * persistent view can also run on a different node, provided that a replicated journal is used.
 *
 * Implementation classes refer to a persistent actors' message stream by implementing `persistenceId`
 * with the corresponding (shared) identifier value.
 *
 * Views can also store snapshots of internal state by calling [[autoUpdate]]. The snapshots of a view
 * are independent of those of the referenced persistent actor. During recovery, a saved snapshot is offered
 * to the view with a [[SnapshotOffer]] message, followed by replayed messages, if any, that are younger
 * than the snapshot. Default is to offer the latest saved snapshot.
 *
 * By default, a view automatically updates itself with an interval returned by `autoUpdateInterval`.
 * This method can be overridden by implementation classes to define a view instance-specific update
 * interval. The default update interval for all views of an actor system can be configured with the
 * `akka.persistence.view.auto-update-interval` configuration key. Applications may trigger additional
 * view updates by sending the view [[Update]] requests. See also methods
 *
 *  - [[autoUpdate]] for turning automated updates on or off
 *  - [[autoUpdateReplayMax]] for limiting the number of replayed messages per view update cycle
 */
trait PersistentView extends Actor with Snapshotter with Stash with StashFactory {
  import PersistentView._
  import JournalProtocol._
  import SnapshotProtocol.LoadSnapshotResult
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val viewSettings = extension.settings.view
  private lazy val journal = extension.journalFor(persistenceId)

  private var schedule: Option[Cancellable] = None

  private var _lastSequenceNr: Long = 0L
  private val internalStash = createStash()
  private var currentState: State = recoveryPending

  /**
   * View id is used as identifier for snapshots performed by this [[PersistentView]].
   * This allows the View to keep separate snapshots of data than the [[PersistentActor]] originating the message stream.
   *
   *
   * The usual case is to have a *different* id set as `viewId` than `persistenceId`,
   * although it is possible to share the same id with an [[PersistentActor]] - for example to decide about snapshots
   * based on some average or sum, calculated by this view.
   *
   * Example:
   * {{{
   *    class SummingView extends PersistentView {
   *      override def persistenceId = "count-123"
   *      override def viewId        = "count-123-sum" // this view is performing summing,
   *                                                   // so this view's snapshots reside under the "-sum" suffixed id
   *
   *      // ...
   *    }
   * }}}
   */
  def viewId: String

  /**
   * Id of the persistent entity for which messages should be replayed.
   */
  def persistenceId: String

  /**
   * Returns `viewId`.
   */
  def snapshotterId: String = viewId

  /**
   * If `true`, the currently processed message was persisted (is sent from the Journal).
   * If `false`, the currently processed message comes from another actor (from "user-land").
   */
  def isPersistent: Boolean = currentState.recoveryRunning

  /**
   * If `true`, this view automatically updates itself with an interval specified by `autoUpdateInterval`.
   * If `false`, applications must explicitly update this view by sending [[Update]] requests. The default
   * value can be configured with the `akka.persistence.view.auto-update` configuration key. This method
   * can be overridden by implementation classes to return non-default values.
   */
  def autoUpdate: Boolean =
    viewSettings.autoUpdate

  /**
   * The interval for automated updates. The default value can be configured with the
   * `akka.persistence.view.auto-update-interval` configuration key. This method can be
   * overridden by implementation classes to return non-default values.
   */
  def autoUpdateInterval: FiniteDuration =
    viewSettings.autoUpdateInterval

  /**
   * The maximum number of messages to replay per update. The default value can be configured with the
   * `akka.persistence.view.auto-update-replay-max` configuration key. This method can be overridden by
   * implementation classes to return non-default values.
   */
  def autoUpdateReplayMax: Long =
    viewSettings.autoUpdateReplayMax

  /**
   * Highest received sequence number so far or `0L` if this actor hasn't replayed
   * any persistent events yet.
   */
  def lastSequenceNr: Long = _lastSequenceNr

  /**
   * Returns `lastSequenceNr`.
   */
  def snapshotSequenceNr: Long = lastSequenceNr

  private def setLastSequenceNr(value: Long): Unit =
    _lastSequenceNr = value

  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
    if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr

  /**
   * Triggers an initial recovery, starting form a snapshot, if any, and replaying at most `autoUpdateReplayMax`
   * messages (following that snapshot).
   */
  override def preStart(): Unit = {
    super.preStart()
    self ! Recover(replayMax = autoUpdateReplayMax)
    if (autoUpdate) schedule = Some(context.system.scheduler.schedule(autoUpdateInterval, autoUpdateInterval,
      self, ScheduledUpdate(autoUpdateReplayMax)))
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundReceive(receive: Receive, message: Any): Unit = {
    currentState.stateReceive(receive, message)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    try internalStash.unstashAll() finally super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    schedule.foreach(_.cancel())
    super.postStop()
  }

  override def unhandled(message: Any): Unit = {
    message match {
      case RecoveryCompleted ⇒ // mute
      case RecoveryFailure(cause) ⇒
        val errorMsg = s"PersistentView killed after recovery failure (persisten id = [${persistenceId}]). " +
          "To avoid killing persistent actors on recovery failure, a PersistentView must handle RecoveryFailure messages. " +
          "RecoveryFailure was caused by: " + cause
        throw new ActorKilledException(errorMsg)
      case m ⇒ super.unhandled(m)
    }
  }

  private def changeState(state: State): Unit = {
    currentState = state
  }

  // TODO There are some duplication of the recovery state management here and in Eventsourced.scala,
  //      but the enhanced PersistentView will not be based on recovery infrastructure, and
  //      therefore this code will be replaced anyway

  private trait State {
    def stateReceive(receive: Receive, message: Any): Unit
    def recoveryRunning: Boolean
  }

  /**
   * Initial state, waits for `Recover` request, and then submits a `LoadSnapshot` request to the snapshot
   * store and changes to `recoveryStarted` state. All incoming messages except `Recover` are stashed.
   */
  private def recoveryPending = new State {
    override def toString: String = "recovery pending"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any): Unit = message match {
      case Recover(fromSnap, toSnr, replayMax) ⇒
        changeState(recoveryStarted(replayMax))
        loadSnapshot(snapshotterId, fromSnap, toSnr)
      case _ ⇒ internalStash.stash()
    }
  }

  /**
   * Processes a loaded snapshot, if any. A loaded snapshot is offered with a `SnapshotOffer`
   * message to the actor's `receive`. Then initiates a message replay, either starting
   * from the loaded snapshot or from scratch, and switches to `replayStarted` state.
   * All incoming messages are stashed.
   *
   * @param replayMax maximum number of messages to replay.
   */
  private def recoveryStarted(replayMax: Long) = new State {

    override def toString: String = s"recovery started (replayMax = [${replayMax}])"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any) = message match {
      case r: Recover ⇒ // ignore
      case LoadSnapshotResult(sso, toSnr) ⇒
        sso.foreach {
          case SelectedSnapshot(metadata, snapshot) ⇒
            setLastSequenceNr(metadata.sequenceNr)
            // Since we are recovering we can ignore the receive behavior from the stack
            PersistentView.super.aroundReceive(receive, SnapshotOffer(metadata, snapshot))
        }
        changeState(replayStarted(await = true))
        journal ! ReplayMessages(lastSequenceNr + 1L, toSnr, replayMax, persistenceId, self)
      case other ⇒ internalStash.stash()
    }
  }

  /**
   * Processes replayed messages, if any. The actor's `receive` is invoked with the replayed
   * events.
   *
   * If replay succeeds it switches to `initializing` state and requests the highest stored sequence
   * number from the journal. Otherwise RecoveryFailure is emitted.
   * If replay succeeds the `onReplaySuccess` callback method is called, otherwise `onReplayFailure`.
   *
   * If processing of a replayed event fails, the exception is caught and
   * stored for later `RecoveryFailure` message and state is changed to `recoveryFailed`.
   *
   * All incoming messages are stashed.
   */
  private def replayStarted(await: Boolean) = new State {
    override def toString: String = s"replay started"
    override def recoveryRunning: Boolean = true

    private var stashUpdate = await

    override def stateReceive(receive: Receive, message: Any) = message match {
      case ScheduledUpdate(_) ⇒ // ignore
      case Update(false, _)   ⇒ // ignore
      case u @ Update(true, _) if !stashUpdate ⇒
        stashUpdate = true
        internalStash.stash()
      case r: Recover ⇒ // ignore
      case ReplayedMessage(p) ⇒
        try {
          updateLastSequenceNr(p)
          PersistentView.super.aroundReceive(receive, p.payload)
        } catch {
          case NonFatal(t) ⇒
            changeState(replayFailed(t, p))
        }
      case ReplayMessagesSuccess ⇒
        onReplayComplete(await)
      case ReplayMessagesFailure(cause) ⇒
        onReplayComplete(await)
        PersistentView.super.aroundReceive(receive, RecoveryFailure(cause)(None))
      case other ⇒
        internalStash.stash()
    }

    /**
     * Switches to `idle` state and schedules the next update if `autoUpdate` returns `true`.
     */
    private def onReplayComplete(await: Boolean): Unit = {
      changeState(idle)
      if (await) internalStash.unstashAll()
    }
  }

  /**
   * Consumes remaining replayed messages and then emits RecoveryFailure to the
   * `receive` behavior.
   */
  private def replayFailed(cause: Throwable, failed: PersistentRepr) = new State {

    override def toString: String = "replay failed"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any) = message match {
      case ReplayedMessage(p) ⇒ updateLastSequenceNr(p)
      case ReplayMessagesFailure(_) ⇒
        replayCompleted(receive)
        // journal couldn't tell the maximum stored sequence number, hence the next
        // replay must be a full replay (up to the highest stored sequence number)
        // Recover(lastSequenceNr) is sent by preRestart
        setLastSequenceNr(Long.MaxValue)
      case ReplayMessagesSuccess ⇒ replayCompleted(receive)
      case r: Recover            ⇒ // ignore
      case _                     ⇒ internalStash.stash()
    }

    def replayCompleted(receive: Receive): Unit = {
      // in case the actor resumes the state must be `idle`
      changeState(idle)

      PersistentView.super.aroundReceive(receive, RecoveryFailure(cause)(Some((failed.sequenceNr, failed.payload))))
    }
  }

  /**
   * When receiving an [[Update]] request, switches to `replayStarted` state and triggers
   * an incremental message replay. Invokes the actor's current behavior for any other
   * received message.
   */
  private val idle: State = new State {
    override def toString: String = "idle"
    override def recoveryRunning: Boolean = false

    override def stateReceive(receive: Receive, message: Any): Unit = message match {
      case r: Recover                     ⇒ // ignore
      case ScheduledUpdate(replayMax)     ⇒ changeStateToReplayStarted(await = false, replayMax)
      case Update(awaitUpdate, replayMax) ⇒ changeStateToReplayStarted(awaitUpdate, replayMax)
      case other                          ⇒ PersistentView.super.aroundReceive(receive, other)
    }

    def changeStateToReplayStarted(await: Boolean, replayMax: Long): Unit = {
      changeState(replayStarted(await))
      journal ! ReplayMessages(lastSequenceNr + 1L, Long.MaxValue, replayMax, persistenceId, self)
    }
  }

}

/**
 * Java API.
 *
 * @see [[PersistentView]]
 */
abstract class UntypedPersistentView extends UntypedActor with PersistentView

/**
 * Java API: compatible with lambda expressions (to be used with [[akka.japi.pf.ReceiveBuilder]])
 *
 * @see [[PersistentView]]
 */
abstract class AbstractPersistentView extends AbstractActor with PersistentView
