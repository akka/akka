/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.StoppedBehavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.Eventsourced.{ PendingHandlerInvocation, StashingHandlerInvocation }
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.journal.Tagged
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * INTERNAL API
 *
 * Fourth (of four) -- also known as 'final' or 'ultimate' -- form of PersistentBehavior.
 *
 * In this phase recovery has completed successfully and we continue handling incoming commands,
 * as well as persisting new events as dictated by the user handlers.
 *
 * This behavior operates in two phases:
 * - HandlingCommands - where the command handler is invoked for incoming commands
 * - PersistingEvents - where incoming commands are stashed until persistence completes
 *
 * This is implemented as such to avoid creating many EventsourcedRunning instances,
 * which perform the Persistence extension lookup on creation and similar things (config lookup)
 *
 */
@InternalApi
class EventsourcedRunning[Command, Event, State](
  val persistenceId:          String,
  override val context:       ActorContext[Any],
  override val timers:        TimerScheduler[Any],
  override val internalStash: StashBuffer[Any],

  private var sequenceNr: Long,
  val writerIdentity:     WriterIdentity,

  private var state: State,

  val callbacks: EventsourcedCallbacks[Command, Event, State],
  val pluginIds: EventsourcedPluginIds
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement { same ⇒

  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected val log = Logging(context.system.toUntyped, this)

  private def commandContext: ActorContext[Command] = context.asInstanceOf[ActorContext[Command]]

  // ----------

  // Holds callbacks for persist calls (note that we do not implement persistAsync currently)
  private def hasNoPendingInvocations: Boolean = pendingInvocations.isEmpty
  private val pendingInvocations = new java.util.LinkedList[PendingHandlerInvocation]() // we only append / isEmpty / get(0) on it

  // ----------

  private def snapshotSequenceNr: Long = sequenceNr

  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
    if (persistent.sequenceNr > sequenceNr) sequenceNr = persistent.sequenceNr
  private def nextSequenceNr(): Long = {
    sequenceNr += 1L
    sequenceNr
  }
  // ----------

  private def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[Any] = {
    response match {
      case SaveSnapshotSuccess(meta) ⇒
        log.debug("Save snapshot successful: " + meta)
        same
      case SaveSnapshotFailure(meta, ex) ⇒
        log.error(ex, "Save snapshot failed! " + meta)
        same // FIXME https://github.com/akka/akka/issues/24637 should we provide callback for this? to allow Stop
    }
  }

  // ----------

  trait EventsourcedRunningPhase {
    def name: String
    def onCommand(c: Command): Behavior[Any]
    def onJournalResponse(response: JournalProtocol.Response): Behavior[Any]
  }

  object HandlingCommands extends EventsourcedRunningPhase {
    def name = "HandlingCommands"

    final override def onCommand(command: Command): Behavior[Any] = {
      val effect = commandHandler(commandContext, state, command)
      applyEffects(command, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
    }
    final override def onJournalResponse(response: Response): Behavior[Any] = {
      // should not happen, what would it reply?
      throw new RuntimeException("Received message which should not happen in Running state!")
    }
  }

  object PersistingEventsNoSideEffects extends PersistingEvents(Nil)

  sealed class PersistingEvents(sideEffects: immutable.Seq[ChainableEffect[_, S]]) extends EventsourcedRunningPhase {
    def name = "PersistingEvents"

    final override def onCommand(c: Command): Behavior[Any] = {
      stash(c)
      same
    }

    final override def onJournalResponse(response: Response): Behavior[Any] = {
      log.debug("Received Journal response: {}", response)
      response match {
        case WriteMessageSuccess(p, id) ⇒
          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
          // while message is in flight, in that case we ignore the call to the handler
          if (id == writerIdentity.instanceId) {
            updateLastSequenceNr(p)
            popApplyHandler(p.payload)
            onWriteMessageComplete()
            tryUnstash(context, applySideEffects(sideEffects))
          } else same

        case WriteMessageRejected(p, cause, id) ⇒
          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
          // while message is in flight, in that case the handler has already been discarded
          if (id == writerIdentity.instanceId) {
            updateLastSequenceNr(p)
            onPersistRejected(cause, p.payload, p.sequenceNr) // does not stop
            tryUnstash(context, applySideEffects(sideEffects))
          } else same

        case WriteMessageFailure(p, cause, id) ⇒
          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
          // while message is in flight, in that case the handler has already been discarded
          if (id == writerIdentity.instanceId) {
            onWriteMessageComplete()
            onPersistFailureThenStop(cause, p.payload, p.sequenceNr)
          } else same

        case WriteMessagesSuccessful ⇒
          // ignore
          same

        case WriteMessagesFailed(_) ⇒
          // ignore
          same // it will be stopped by the first WriteMessageFailure message; not applying side effects

        case _: LoopMessageSuccess ⇒
          // ignore, should never happen as there is no persistAsync in typed
          same
      }
    }

    private def onWriteMessageComplete(): Unit =
      tryBecomeHandlingCommands()

    private def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
      log.error(
        cause,
        "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
        event.getClass.getName, seqNr, persistenceId, cause.getMessage)
    }

    private def onPersistFailureThenStop(cause: Throwable, event: Any, seqNr: Long): Behavior[Any] = {
      log.error(cause, "Failed to persist event type [{}] with sequence number [{}] for persistenceId [{}].",
        event.getClass.getName, seqNr, persistenceId)

      // FIXME see #24479 for reconsidering the stopping behaviour
      Behaviors.stopped
    }

  }

  // the active phase switches between PersistingEvents and HandlingCommands;
  // we do this via a var instead of behaviours to keep allocations down as this will be flip/flaping on every Persist effect
  private[this] var phase: EventsourcedRunningPhase = HandlingCommands

  override def onMessage(msg: Any): Behavior[Any] = {
    msg match {
      // TODO explore crazy hashcode hack to make this match quicker...?
      case SnapshotterResponse(r) ⇒ onSnapshotterResponse(r)
      case JournalResponse(r)     ⇒ phase.onJournalResponse(r)
      case command: Command @unchecked ⇒
        // the above type-check does nothing, since Command is tun
        // we cast explicitly to fail early in case of type mismatch
        val c = command.asInstanceOf[Command]
        phase.onCommand(c)
    }
  }

  // ----------

  def applySideEffects(effects: immutable.Seq[ChainableEffect[_, S]]): Behavior[Any] = {
    var res: Behavior[Any] = same
    val it = effects.iterator

    // if at least one effect results in a `stop`, we need to stop
    // manual loop implementation to avoid allocations and multiple scans
    while (it.hasNext) {
      val effect = it.next()
      applySideEffect(effect) match {
        case _: StoppedBehavior[_] ⇒ res = Behaviors.stopped
        case _                     ⇒ // nothing to do
      }
    }

    res
  }

  def applySideEffect(effect: ChainableEffect[_, S]): Behavior[Any] = effect match {
    case _: Stop.type @unchecked ⇒
      Behaviors.stopped

    case SideEffect(sideEffects) ⇒
      sideEffects(state)
      same

    case _ ⇒
      throw new IllegalArgumentException(s"Not supported effect detected [${effect.getClass.getName}]!")
  }

  def applyEvent(s: S, event: E): S =
    eventHandler(s, event)

  @tailrec private def applyEffects(msg: Any, effect: EffectImpl[E, S], sideEffects: immutable.Seq[ChainableEffect[_, S]] = Nil): Behavior[Any] = {
    if (log.isDebugEnabled)
      log.debug(s"Handled command [{}], resulting effect: [{}], side effects: [{}]", msg.getClass.getName, effect, sideEffects.size)

    effect match {
      case CompositeEffect(e, currentSideEffects) ⇒
        // unwrap and accumulate effects
        applyEffects(msg, e, currentSideEffects ++ sideEffects)

      case Persist(event) ⇒
        // apply the event before persist so that validation exception is handled before persisting
        // the invalid event, in case such validation is implemented in the event handler.
        // also, ensure that there is an event handler for each single event
        state = applyEvent(state, event)
        val tags = tagger(event)
        val eventToPersist = if (tags.isEmpty) event else Tagged(event, tags)

        internalPersist(eventToPersist, sideEffects) { _ ⇒
          if (snapshotWhen(state, event, sequenceNr))
            internalSaveSnapshot(state)
        }

      case PersistAll(events) ⇒
        if (events.nonEmpty) {
          // apply the event before persist so that validation exception is handled before persisting
          // the invalid event, in case such validation is implemented in the event handler.
          // also, ensure that there is an event handler for each single event
          var count = events.size
          var seqNr = sequenceNr
          val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, false)) {
            case ((currentState, snapshot), event) ⇒
              seqNr += 1
              val shouldSnapshot = snapshot || snapshotWhen(currentState, event, seqNr)
              (applyEvent(currentState, event), shouldSnapshot)
          }
          state = newState
          val eventsToPersist = events.map { event ⇒
            val tags = tagger(event)
            if (tags.isEmpty) event else Tagged(event, tags)
          }

          internalPersistAll(eventsToPersist, sideEffects) { _ ⇒
            count -= 1
            if (count == 0) {
              sideEffects.foreach(applySideEffect)
              if (shouldSnapshotAfterPersist)
                internalSaveSnapshot(state)
            }
          }
        } else {
          // run side-effects even when no events are emitted
          tryUnstash(context, applySideEffects(sideEffects))
        }

      case e: PersistNothing.type @unchecked ⇒
        tryUnstash(context, applySideEffects(sideEffects))

      case _: Unhandled.type @unchecked ⇒
        applySideEffects(sideEffects)
        Behavior.unhandled

      case c: ChainableEffect[_, S] ⇒
        applySideEffect(c)
    }
  }

  private def popApplyHandler(payload: Any): Unit =
    pendingInvocations.pop().handler(payload)

  private def becomePersistingEvents(sideEffects: immutable.Seq[ChainableEffect[_, S]]): Behavior[Any] = {
    if (phase.isInstanceOf[PersistingEvents]) throw new IllegalArgumentException(
      "Attempted to become PersistingEvents while already in this phase! Logic error?")

    phase =
      if (sideEffects.isEmpty) PersistingEventsNoSideEffects
      else new PersistingEvents(sideEffects)

    same
  }

  private def tryBecomeHandlingCommands(): Behavior[Any] = {
    if (phase == HandlingCommands) throw new IllegalArgumentException(
      "Attempted to become HandlingCommands while already in this phase! Logic error?")

    if (hasNoPendingInvocations) { // CAN THIS EVER NOT HAPPEN?
      phase = HandlingCommands
    }

    same
  }

  // ---------- journal interactions ---------

  // Any since can be `E` or `Tagged`
  private def internalPersist(event: Any, sideEffects: immutable.Seq[ChainableEffect[_, S]])(handler: Any ⇒ Unit): Behavior[Any] = {
    pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])

    val senderNotKnownBecauseAkkaTyped = null
    val repr = PersistentRepr(event, persistenceId = persistenceId, sequenceNr = nextSequenceNr(), writerUuid = writerIdentity.writerUuid, sender = senderNotKnownBecauseAkkaTyped)

    val eventBatch = AtomicWrite(repr) :: Nil // batching not used, since no persistAsync
    journal.tell(JournalProtocol.WriteMessages(eventBatch, selfUntypedAdapted, writerIdentity.instanceId), selfUntypedAdapted)

    becomePersistingEvents(sideEffects)
  }

  private def internalPersistAll(events: immutable.Seq[Any], sideEffects: immutable.Seq[ChainableEffect[_, S]])(handler: Any ⇒ Unit): Behavior[Any] = {
    if (events.nonEmpty) {
      val senderNotKnownBecauseAkkaTyped = null

      events.foreach { event ⇒
        pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      }

      val write = AtomicWrite(events.map(PersistentRepr.apply(_, persistenceId = persistenceId,
        sequenceNr = nextSequenceNr(), writerUuid = writerIdentity.writerUuid, sender = senderNotKnownBecauseAkkaTyped)))

      journal.tell(JournalProtocol.WriteMessages(write :: Nil, selfUntypedAdapted, writerIdentity.instanceId), selfUntypedAdapted)

      becomePersistingEvents(sideEffects)
    } else same
  }

  private def internalSaveSnapshot(snapshot: State): Unit = {
    snapshotStore.tell(SnapshotProtocol.SaveSnapshot(SnapshotMetadata(persistenceId, snapshotSequenceNr), snapshot), selfUntypedAdapted)
  }

  override def toString = s"EventsourcedRunning($persistenceId,${phase.name})"
}
