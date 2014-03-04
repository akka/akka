/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._

import akka.actor._
import akka.persistence.JournalProtocol._

/**
 * Instructs a [[View]] to update itself. This will run a single incremental message replay with all
 * messages from the corresponding processor's journal that have not yet been consumed by the view.
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
case class Update(await: Boolean = false, replayMax: Long = Long.MaxValue)

case object Update {
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
 * A view replicates the persistent message stream of a processor. Implementation classes receive the
 * message stream as [[Persistent]] messages. These messages can be processed to update internal state
 * in order to maintain an (eventual consistent) view of the state of the corresponding processor. A
 * view can also run on a different node, provided that a replicated journal is used. Implementation
 * classes reference a processor by implementing `processorId`.
 *
 * Views can also store snapshots of internal state by calling [[#saveSnapshot]]. The snapshots of a view
 * are independent of those of the referenced processor. During recovery, a saved snapshot is offered
 * to the view with a [[SnapshotOffer]] message, followed by replayed messages, if any, that are younger
 * than the snapshot. Default is to offer the latest saved snapshot.
 *
 * By default, a view automatically updates itself with an interval returned by `autoUpdateInterval`.
 * This method can be overridden by implementation classes to define a view instance-specific update
 * interval. The default update interval for all views of an actor system can be configured with the
 * `akka.persistence.view.auto-update-interval` configuration key. Applications may trigger additional
 * view updates by sending the view [[Update]] requests. See also methods
 *
 *  - [[#autoUpdate]] for turning automated updates on or off
 *  - [[#autoUpdateReplayMax]] for limiting the number of replayed messages per view update cycle
 *
 * Views can also use channels to communicate with destinations in the same way as processors can do.
 */
trait View extends Actor with Recovery {
  import context.dispatcher

  /**
   * INTERNAL API.
   *
   * Extends the `replayStarted` state of [[Recovery]] with logic to handle [[Update]] requests
   * sent by users.
   */
  private[persistence] override def replayStarted(await: Boolean) = new State {
    private var delegateAwaiting = await
    private var delegate = View.super.replayStarted(await)

    override def toString: String = delegate.toString

    override def aroundReceive(receive: Receive, message: Any) = message match {
      case Update(false, _) ⇒ // ignore
      case u @ Update(true, _) if !delegateAwaiting ⇒
        delegateAwaiting = true
        delegate = View.super.replayStarted(await = true)
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
        journal ! ReplayMessages(lastSequenceNr + 1L, Long.MaxValue, replayMax, processorId, self)
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

  private val _viewId = extension.processorId(self)
  private val viewSettings = extension.settings.view

  private var schedule: Option[Cancellable] = None

  /**
   * View id. Defaults to this view's path and can be overridden.
   */
  def viewId: String = _viewId

  /**
   * Returns `viewId`.
   */
  def snapshotterId: String = viewId

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
 * @see [[View]]
 */
abstract class UntypedView extends UntypedActor with View

/**
 * Java API: compatible with lambda expressions (to be used with [[akka.japi.pf.ReceiveBuilder]])
 *
 * @see [[View]]
 */
abstract class AbstractView extends View
