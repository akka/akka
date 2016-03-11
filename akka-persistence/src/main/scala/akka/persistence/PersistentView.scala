/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.actor.AbstractActor
import akka.actor.Actor
import akka.actor.Cancellable
import akka.actor.Stash
import akka.actor.StashFactory
import akka.actor.UntypedActor
import akka.actor.ActorLogging

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
 * Views can also store snapshots of internal state by calling [[PersistentView#autoUpdate]]. The snapshots of a view
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
 *  - [[PersistentView#autoUpdate]] for turning automated updates on or off
 *  - [[PersistentView#autoUpdateReplayMax]] for limiting the number of replayed messages per view update cycle
 *
 */
@deprecated("use Persistence Query instead", "2.4")
trait PersistentView extends Actor with Snapshotter with Stash with StashFactory
  with PersistenceIdentity with PersistenceRecovery
  with ActorLogging {
  import PersistentView._
  import JournalProtocol._
  import SnapshotProtocol.LoadSnapshotResult
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val viewSettings = extension.settings.view

  private[persistence] lazy val journal = extension.journalFor(journalPluginId)
  private[persistence] lazy val snapshotStore = extension.snapshotStoreFor(snapshotPluginId)

  private var schedule: Option[Cancellable] = None

  private var _lastSequenceNr: Long = 0L
  private val internalStash = createStash()
  private var currentState: State = recoveryStarted(Long.MaxValue)

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
    viewSettings.autoUpdateReplayMax match {
      case -1    ⇒ Long.MaxValue
      case value ⇒ value
    }

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

  override def recovery = Recovery(replayMax = autoUpdateReplayMax)

  /**
   * Triggers an initial recovery, starting form a snapshot, if any, and replaying at most `autoUpdateReplayMax`
   * messages (following that snapshot).
   */
  override def preStart(): Unit = {
    startRecovery(recovery)
    if (autoUpdate)
      schedule = Some(context.system.scheduler.schedule(autoUpdateInterval, autoUpdateInterval, self, ScheduledUpdate(autoUpdateReplayMax)))
  }

  private def startRecovery(recovery: Recovery): Unit = {
    changeState(recoveryStarted(recovery.replayMax))
    loadSnapshot(snapshotterId, recovery.fromSnapshot, recovery.toSequenceNr)
  }

  /** INTERNAL API. */
  override protected[akka] def aroundReceive(receive: Receive, message: Any): Unit =
    currentState.stateReceive(receive, message)

  /** INTERNAL API. */
  override protected[akka] def aroundPreStart(): Unit = {
    // Fail fast on missing plugins.
    val j = journal; val s = snapshotStore
    super.aroundPreStart()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    try internalStash.unstashAll() finally super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    schedule.foreach(_.cancel())
    super.postStop()
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   * Subclass may override to customize logging.
   * The `PersistentView` will not stop or throw exception due to this.
   * It will try again on next update.
   */
  protected def onReplayError(cause: Throwable): Unit = {
    log.error(cause, "Persistence view failure when replaying events for persistenceId [{}]. " +
      "Last known sequence number [{}]", persistenceId, lastSequenceNr)
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
      case LoadSnapshotResult(sso, toSnr) ⇒
        sso.foreach {
          case SelectedSnapshot(metadata, snapshot) ⇒
            setLastSequenceNr(metadata.sequenceNr)
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
   * number from the journal.
   *
   * If replay succeeds the `onReplaySuccess` callback method is called, otherwise `onReplayError` is called and
   * remaining replay events are consumed (ignored).
   *
   * If processing of a replayed event fails, the exception is caught and
   * stored for later and state is changed to `recoveryFailed`.
   *
   * All incoming messages are stashed when `await` is true.
   */
  private def replayStarted(await: Boolean) = new State {
    override def toString: String = s"replay started"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any) = message match {
      case ReplayedMessage(p) ⇒
        try {
          updateLastSequenceNr(p)
          PersistentView.super.aroundReceive(receive, p.payload)
        } catch {
          case NonFatal(t) ⇒
            changeState(ignoreRemainingReplay(t))
        }
      case _: RecoverySuccess ⇒
        onReplayComplete()
      case ReplayMessagesFailure(cause) ⇒
        try onReplayError(cause) finally onReplayComplete()
      case ScheduledUpdate(_) ⇒ // ignore
      case Update(a, _) ⇒
        if (a)
          internalStash.stash()
      case other ⇒
        if (await)
          internalStash.stash()
        else {
          try {
            PersistentView.super.aroundReceive(receive, other)
          } catch {
            case NonFatal(t) ⇒
              changeState(ignoreRemainingReplay(t))
          }
        }
    }

    /**
     * Switches to `idle`
     */
    private def onReplayComplete(): Unit = {
      changeState(idle)
      internalStash.unstashAll()
    }
  }

  /**
   * Consumes remaining replayed messages and then throw the exception.
   */
  private def ignoreRemainingReplay(cause: Throwable) = new State {

    override def toString: String = "replay failed"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any) = message match {
      case ReplayedMessage(p) ⇒
      case ReplayMessagesFailure(_) ⇒
        replayCompleted(receive)
        // journal couldn't tell the maximum stored sequence number, hence the next
        // replay must be a full replay (up to the highest stored sequence number)
        // Recover(lastSequenceNr) is sent by preRestart
        setLastSequenceNr(Long.MaxValue)
      case _: RecoverySuccess ⇒ replayCompleted(receive)
      case _                  ⇒ internalStash.stash()
    }

    def replayCompleted(receive: Receive): Unit = {
      // in case the actor resumes the state must be `idle`
      changeState(idle)
      internalStash.unstashAll()
      throw cause
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
      case ReplayedMessage(p) ⇒
        // we can get ReplayedMessage here if it was stashed by user during replay
        // unwrap the payload
        PersistentView.super.aroundReceive(receive, p.payload)
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
@deprecated("use Persistence Query instead", "2.4")
abstract class UntypedPersistentView extends UntypedActor with PersistentView

/**
 * Java API: compatible with lambda expressions (to be used with [[akka.japi.pf.ReceiveBuilder]])
 *
 * @see [[PersistentView]]
 */
@deprecated("use Persistence Query instead", "2.4")
abstract class AbstractPersistentView extends AbstractActor with PersistentView
