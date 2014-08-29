/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._

import akka.actor._
import akka.persistence.JournalProtocol._

/**
 * Instructs a [[PersistentView]] to update itself. This will run a single incremental message replay with
 * all messages from the corresponding persistent id's journal that have not yet been consumed by the view.
 * To update a view with messages that have been written after handling this request, another `Update`
 * request must be sent to the view.
 *
 * @param await if `true`, processing of further messages sent to the view will be delayed until the
 *              incremental message replay, triggered by this update request, completes. If `false`,
 *              any message sent to the view may interleave with replayed [[Persistent]] message
 *              stream.
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
trait PersistentView extends Actor with Recovery {
  import context.dispatcher

  /**
   * INTERNAL API.
   *
   * Extends the `replayStarted` state of [[Recovery]] with logic to handle [[Update]] requests
   * sent by users.
   */
  private[persistence] override def replayStarted(await: Boolean) = new State {
    private var delegateAwaiting = await
    private var delegate = PersistentView.super.replayStarted(await)

    override def toString: String = delegate.toString

    override def aroundReceive(receive: Receive, message: Any) = message match {
      case Update(false, _) ⇒ // ignore
      case u @ Update(true, _) if !delegateAwaiting ⇒
        delegateAwaiting = true
        delegate = PersistentView.super.replayStarted(await = true)
        delegate.aroundReceive(receive, u)
      case other ⇒
        delegate.aroundReceive(receive, other)
    }
  }

  /**
   * When receiving an [[Update]] request, switches to `replayStarted` state and triggers
   * an incremental message replay. Invokes the actor's current behavior for any other
   * received message.
   */
  private val idle: State = new State {
    override def toString: String = "idle"

    def aroundReceive(receive: Receive, message: Any): Unit = message match {
      case r: Recover ⇒ // ignore
      case Update(awaitUpdate, replayMax) ⇒
        _currentState = replayStarted(await = awaitUpdate)
        journal ! ReplayMessages(lastSequenceNr + 1L, Long.MaxValue, replayMax, persistenceId, self)
      case other ⇒ process(receive, other)
    }
  }

  /**
   * INTERNAL API.
   */
  private[persistence] def onReplaySuccess(receive: Receive, await: Boolean): Unit =
    onReplayComplete(await)

  /**
   * INTERNAL API.
   */
  private[persistence] def onReplayFailure(receive: Receive, await: Boolean, cause: Throwable): Unit =
    onReplayComplete(await)

  /**
   * Switches to `idle` state and schedules the next update if `autoUpdate` returns `true`.
   */
  private def onReplayComplete(await: Boolean): Unit = {
    _currentState = idle
    if (autoUpdate) schedule = Some(context.system.scheduler.scheduleOnce(autoUpdateInterval, self, Update(await = false, autoUpdateReplayMax)))
    if (await) receiverStash.unstashAll()
  }

  /**
   * INTERNAL API
   * WARNING: This implementation UNWRAPS PERSISTENT() before delivering to the receive block.
   */
  override private[persistence] def runReceive(receive: Receive)(msg: Persistent): Unit =
    receive.applyOrElse(msg.payload, unhandled)

  private val viewSettings = extension.settings.view

  private var schedule: Option[Cancellable] = None

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
  def isPersistent: Boolean =
    currentPersistentMessage.isDefined

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
   * Triggers an initial recovery, starting form a snapshot, if any, and replaying at most `autoUpdateReplayMax`
   * messages (following that snapshot).
   */
  override def preStart(): Unit = {
    super.preStart()
    self ! Recover(replayMax = autoUpdateReplayMax)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    try receiverStash.unstashAll() finally super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    schedule.foreach(_.cancel())
    super.postStop()
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
