/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity
import akka.persistence.typed.scaladsl.PersistentBehaviors._
import akka.util.Helpers._

import scala.util.control.NonFatal

/**
 * INTERNAL API
 *
 * Third (of four) behavior of an PersistentBehavior.
 *
 * In this behavior we finally start replaying events, beginning from the last applied sequence number
 * (i.e. the one up-until-which the snapshot recovery has brought us).
 *
 * Once recovery is completed, the actor becomes [[EventsourcedRunning]], stashed messages are flushed
 * and control is given to the user's handlers to drive the actors behavior from there.
 *
 */
@InternalApi
private[akka] class EventsourcedRecoveringEvents[Command, Event, State](
  val setup:                  EventsourcedSetup[Command, Event, State],
  override val context:       ActorContext[Any],
  override val timers:        TimerScheduler[Any],
  override val internalStash: StashBuffer[Any],

  private var sequenceNr: Long,
  val writerIdentity:     WriterIdentity,

  private var state: State
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement {
  import setup._
  import Behaviors.same
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected val log = Logging(context.system.toUntyped, this)

  // -------- initialize --------
  startRecoveryTimer()

  replayEvents(sequenceNr + 1L, recovery.toSequenceNr)
  // ---- end of initialize ----

  private def commandContext: ActorContext[Command] = context.asInstanceOf[ActorContext[Command]]

  // ----------

  def snapshotSequenceNr: Long = sequenceNr

  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
    if (persistent.sequenceNr > sequenceNr) sequenceNr = persistent.sequenceNr

  private def setLastSequenceNr(value: Long): Unit =
    sequenceNr = value

  // ----------

  // FIXME it's a bit of a pain to have those lazy vals, change everything to constructor parameters
  lazy val timeout = extension.journalConfigFor(journalPluginId).getMillisDuration("recovery-event-timeout")

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "recovery-tick"
  private def startRecoveryTimer(): Unit = timers.startPeriodicTimer(RecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), timeout)
  private def cancelRecoveryTimer(): Unit = timers.cancel(RecoveryTickTimerKey)

  private var eventSeenInInterval = false

  def onCommand(cmd: Command): Behavior[Any] = {
    // during recovery, stash all incoming commands
    stash(context, cmd)
    same
  }

  def onJournalResponse(response: JournalProtocol.Response): Behavior[Any] = try {
    response match {
      case ReplayedMessage(repr) ⇒
        eventSeenInInterval = true
        updateLastSequenceNr(repr)
        // TODO we need some state adapters here?
        val newState = eventHandler(state, repr.payload.asInstanceOf[Event])
        state = newState
        same

      case RecoverySuccess(highestSeqNr) ⇒
        log.debug("Recovery successful, recovered until sequenceNr: {}", highestSeqNr)
        cancelRecoveryTimer()
        setLastSequenceNr(highestSeqNr)

        try onRecoveryCompleted(state)
        catch { case NonFatal(ex) ⇒ onRecoveryFailure(ex, Some(state)) }

      case ReplayMessagesFailure(cause) ⇒
        onRecoveryFailure(cause, event = None)

      case other ⇒
        stash(context, other)
        Behaviors.same
    }
  } catch {
    case NonFatal(e) ⇒
      cancelRecoveryTimer()
      onRecoveryFailure(e, None)
  }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[Any] = {
    log.warning("Unexpected [{}] from SnapshotStore, already in recovering events state.", Logging.simpleName(response))
    Behaviors.same // ignore the response
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param event the event that was processed in `receiveRecover`, if the exception was thrown there
   */
  protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Behavior[Any] = {
    returnRecoveryPermit("on recovery failure: " + cause.getMessage)
    cancelRecoveryTimer()

    event match {
      case Some(evt) ⇒
        log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}].", evt.getClass.getName, sequenceNr)
        Behaviors.stopped

      case None ⇒
        log.error(cause, "Persistence failure when replaying events.  Last known sequence number [{}]", persistenceId, sequenceNr)
        Behaviors.stopped
    }
  }

  protected def onRecoveryCompleted(state: State): Behavior[Any] = {
    try {
      returnRecoveryPermit("recovery completed successfully")
      recoveryCompleted(commandContext, state)

      val running = new EventsourcedRunning[Command, Event, State](
        setup,
        context,
        timers,
        internalStash,

        sequenceNr,
        writerIdentity,

        state
      )

      tryUnstash(context, running)
    } finally {
      cancelRecoveryTimer()
    }
  }

  protected def onRecoveryTick(snapshot: Boolean): Behavior[Any] =
    if (!snapshot) {
      if (!eventSeenInInterval) {
        cancelRecoveryTimer()
        val msg = s"Recovery timed out, didn't get event within $timeout, highest sequence number seen $sequenceNr"
        onRecoveryFailure(new RecoveryTimedOut(msg), event = None) // TODO allow users to hook into this?
      } else {
        eventSeenInInterval = false
        same
      }
    } else {
      // snapshot timeout, but we're already in the events recovery phase
      Behavior.unhandled
    }

  // ----------

  override def onMessage(msg: Any): Behavior[Any] = {
    msg match {
      // TODO explore crazy hashcode hack to make this match quicker...?
      case JournalResponse(r)          ⇒ onJournalResponse(r)
      case RecoveryTickEvent(snapshot) ⇒ onRecoveryTick(snapshot = snapshot)
      case SnapshotterResponse(r)      ⇒ onSnapshotterResponse(r)
      case c: Command @unchecked       ⇒ onCommand(c.asInstanceOf[Command]) // explicit cast to fail eagerly
    }
  }

  // ----------

  // ---------- journal interactions ---------

  private def replayEvents(fromSeqNr: SeqNr, toSeqNr: SeqNr): Unit = {
    log.debug("Replaying messages: from: {}, to: {}", fromSeqNr, toSeqNr)
    // reply is sent to `selfUntypedAdapted`, it is important to target that one
    journal ! ReplayMessages(fromSeqNr, toSeqNr, recovery.replayMax, persistenceId, selfUntypedAdapted)
  }

  private def returnRecoveryPermit(reason: String): Unit = {
    log.debug("Returning recovery permit, reason: " + reason)
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    extension.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRecoveringEvents($persistenceId)"

}
