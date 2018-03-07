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
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol.{ IncomingCommand, JournalResponse, RecoveryTickEvent, SnapshotterResponse }

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
@InternalApi private[akka] object EventsourcedRunning {

  final case class EventsourcedState[State](
    seqNr:              Long,
    state:              State,
    pendingInvocations: immutable.Seq[PendingHandlerInvocation] = Nil
  ) {

    def nextSequenceNr(): EventsourcedState[State] =
      copy(seqNr = seqNr + 1)

    def updateLastSequenceNr(persistent: PersistentRepr): EventsourcedState[State] =
      if (persistent.sequenceNr > seqNr) copy(seqNr = persistent.sequenceNr) else this

    def popApplyPendingInvocation(repr: PersistentRepr): EventsourcedState[State] = {
      val (headSeq, remainingInvocations) = pendingInvocations.splitAt(1)
      headSeq.head.handler(repr.payload)

      copy(
        pendingInvocations = remainingInvocations,
        seqNr = repr.sequenceNr
      )
    }

    def applyEvent[C, E](setup: EventsourcedSetup[C, E, State], event: E): EventsourcedState[State] = {
      val updated = setup.eventHandler(state, event)
      copy(state = updated)
    }
  }

  def apply[C, E, S](setup: EventsourcedSetup[C, E, S], state: EventsourcedState[S]): Behavior[InternalProtocol] =
    new EventsourcedRunning(setup).handlingCommands(state)
}

// ===============================================

@InternalApi private[akka] class EventsourcedRunning[C, E, S](override val setup: EventsourcedSetup[C, E, S])
  extends EventsourcedJournalInteractions[C, E, S] with EventsourcedStashManagement[C, E, S] {
  import EventsourcedRunning.EventsourcedState

  def handlingCommands(state: EventsourcedState[S]): Behavior[InternalProtocol] = {
    withMdc("run-cmnds") {
      Behaviors.immutable[EventsourcedBehavior.InternalProtocol] {
        case (_, SnapshotterResponse(r))           ⇒ Behaviors.unhandled
        case (_, JournalResponse(r))               ⇒ Behaviors.unhandled
        case (_, IncomingCommand(c: C @unchecked)) ⇒ onCommand(state, c)
      }
    }
  }

  private def withMdc(phase: String)(wrapped: Behavior[InternalProtocol]) = {
    val mdc = Map(
      "persistenceId" → setup.persistenceId,
      "phase" → phase
    )

    Behaviors.withMdc((_: Any) ⇒ mdc, wrapped)
  }

  private def onCommand(state: EventsourcedState[S], cmd: C): Behavior[InternalProtocol] = {
    val effect = setup.commandHandler(setup.commandContext, state.state, cmd)
    applyEffects(cmd, state, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
  }

  @tailrec private def applyEffects(
    msg:         Any,
    state:       EventsourcedState[S],
    effect:      EffectImpl[E, S],
    sideEffects: immutable.Seq[ChainableEffect[_, S]] = Nil
  ): Behavior[InternalProtocol] = {
    import setup.log

    if (log.isDebugEnabled)
      log.debug(s"Handled command [{}], resulting effect: [{}], side effects: [{}]", msg.getClass.getName, effect, sideEffects.size)

    effect match {
      case CompositeEffect(eff, currentSideEffects) ⇒
        // unwrap and accumulate effects
        applyEffects(msg, state, eff, currentSideEffects ++ sideEffects)

      case Persist(event) ⇒
        // apply the event before persist so that validation exception is handled before persisting
        // the invalid event, in case such validation is implemented in the event handler.
        // also, ensure that there is an event handler for each single event
        val newState = state.applyEvent(setup, event)
        val eventToPersist = tagEvent(event)

        val newState2 = internalPersist(newState, eventToPersist)

        val handler: Any ⇒ Unit = { x ⇒ // TODO is x the new state?
          if (setup.snapshotWhen(newState2.state, event, newState2.seqNr))
            internalSaveSnapshot(state)
        }
        val pendingInvocations = StashingHandlerInvocation(event, handler) :: Nil

        // FIXME applySideEffects is missing

        persistingEvents(newState2, pendingInvocations, sideEffects)

      case PersistAll(events) ⇒
        if (events.nonEmpty) {
          // apply the event before persist so that validation exception is handled before persisting
          // the invalid event, in case such validation is implemented in the event handler.
          // also, ensure that there is an event handler for each single event
          var count = events.size
          // var seqNr = state.seqNr
          val (newState, shouldSnapshotAfterPersist) =
            events.foldLeft((state, false)) {
              case ((currentState, snapshot), event) ⇒
                val value = currentState
                  .nextSequenceNr() // FIXME seqNr is also incremented in internalPersistAll
                  .applyEvent(setup, event)

                val shouldSnapshot = snapshot || setup.snapshotWhen(value.state, event, value.seqNr)
                (value, shouldSnapshot)
            }

          val eventsToPersist = events.map(tagEvent)

          val newState2 = internalPersistAll(eventsToPersist, newState)

          val handler: Any ⇒ Unit = { _ ⇒
            count -= 1
            if (count == 0) {
              //                // FIXME the result of applying side effects is ignored
              //                val b = applySideEffects(sideEffects, newState)
              if (shouldSnapshotAfterPersist)
                internalSaveSnapshot(newState)
            }
          }

          val pendingInvocations = events map { event ⇒
            StashingHandlerInvocation(event, handler)
          }

          persistingEvents(newState2, pendingInvocations, sideEffects)

        } else {
          // run side-effects even when no events are emitted
          tryUnstash(applySideEffects(sideEffects, state))
        }

      case _: PersistNothing.type @unchecked ⇒
        tryUnstash(applySideEffects(sideEffects, state))

      case _: Unhandled.type @unchecked ⇒
        applySideEffects(sideEffects, state)
        Behavior.unhandled

      case c: ChainableEffect[_, S] ⇒
        applySideEffect(c, state)
    }
  }

  private def tagEvent(event: E): Any = {
    val tags = setup.tagger(event)
    if (tags.isEmpty) event else Tagged(event, tags)
  }

  // ===============================================

  def persistingEvents(
    state:              EventsourcedState[S],
    pendingInvocations: immutable.Seq[PendingHandlerInvocation],
    sideEffects:        immutable.Seq[ChainableEffect[_, S]]
  ): Behavior[InternalProtocol] = {
    withMdc {
      Behaviors.immutable[EventsourcedBehavior.InternalProtocol] {
        case (_, SnapshotterResponse(r))            ⇒ onSnapshotterResponse(r)
        case (_, JournalResponse(r))                ⇒ onJournalResponse(state, pendingInvocations, sideEffects, r)
        case (_, in: IncomingCommand[C @unchecked]) ⇒ onCommand(state, in)
      }
    }
  }

  private def withMdc(wrapped: Behavior[InternalProtocol]) = {
    val mdc = Map(
      "persistenceId" → setup.persistenceId,
      "phase" → "run-persist-evnts"
    )

    Behaviors.withMdc((_: Any) ⇒ mdc, wrapped)
  }

  def onCommand(state: EventsourcedRunning.EventsourcedState[S], cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
    stash(cmd)
    Behaviors.same
  }

  final def onJournalResponse(
    state:              EventsourcedState[S],
    pendingInvocations: immutable.Seq[PendingHandlerInvocation],
    sideEffects:        immutable.Seq[ChainableEffect[_, S]],
    response:           Response): Behavior[InternalProtocol] = {
    setup.log.debug("Received Journal response: {}", response)
    response match {
      case WriteMessageSuccess(p, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case we ignore the call to the handler
        if (id == setup.writerIdentity.instanceId) {
          val newState = state.popApplyPendingInvocation(p)

          // only once all things are applied we can revert back
          if (newState.pendingInvocations.nonEmpty) Behaviors.same
          else tryUnstash(applySideEffects(sideEffects, newState))
        } else Behaviors.same

      case WriteMessageRejected(p, cause, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case the handler has already been discarded
        if (id == setup.writerIdentity.instanceId) {
          val newState = state.updateLastSequenceNr(p)
          onPersistRejected(cause, p.payload, p.sequenceNr) // does not stop (by design)
          tryUnstash(applySideEffects(sideEffects, newState))
        } else Behaviors.same

      case WriteMessageFailure(p, cause, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case the handler has already been discarded
        if (id == setup.writerIdentity.instanceId) {
          // onWriteMessageComplete() -> tryBecomeHandlingCommands
          onPersistFailureThenStop(cause, p.payload, p.sequenceNr)
        } else Behaviors.same

      case WriteMessagesSuccessful ⇒
        // ignore
        Behaviors.same

      case WriteMessagesFailed(_) ⇒
        // ignore
        Behaviors.same // it will be stopped by the first WriteMessageFailure message; not applying side effects

      case _: LoopMessageSuccess ⇒
        // ignore, should never happen as there is no persistAsync in typed
        Behaviors.same
    }
  }

  //    private def onWriteMessageComplete(): Unit =
  //      tryBecomeHandlingCommands()

  private def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    setup.log.error(
      cause,
      "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
      event.getClass.getName, seqNr, setup.persistenceId, cause.getMessage)
  }

  private def onPersistFailureThenStop(cause: Throwable, event: Any, seqNr: Long): Behavior[InternalProtocol] = {
    setup.log.error(cause, "Failed to persist event type [{}] with sequence number [{}] for persistenceId [{}].",
      event.getClass.getName, seqNr, setup.persistenceId)

    // FIXME see #24479 for reconsidering the stopping behaviour
    Behaviors.stopped
  }

  private def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[InternalProtocol] = {
    response match {
      case SaveSnapshotSuccess(meta) ⇒
        setup.context.log.debug("Save snapshot successful: " + meta)
        Behaviors.same
      case SaveSnapshotFailure(meta, ex) ⇒
        setup.context.log.error(ex, "Save snapshot failed! " + meta)
        Behaviors.same // FIXME https://github.com/akka/akka/issues/24637 should we provide callback for this? to allow Stop
    }
  }

  // --------------------------

  def applySideEffects(effects: immutable.Seq[ChainableEffect[_, S]], state: EventsourcedState[S]): Behavior[InternalProtocol] = {
    var res: Behavior[InternalProtocol] = Behaviors.same
    val it = effects.iterator

    // if at least one effect results in a `stop`, we need to stop
    // manual loop implementation to avoid allocations and multiple scans
    while (it.hasNext) {
      val effect = it.next()
      applySideEffect(effect, state) match {
        case _: StoppedBehavior[_] ⇒ res = Behaviors.stopped
        case _                     ⇒ // nothing to do
      }
    }

    res
  }

  def applySideEffect(effect: ChainableEffect[_, S], state: EventsourcedState[S]): Behavior[InternalProtocol] = effect match {
    case _: Stop.type @unchecked ⇒
      Behaviors.stopped

    case SideEffect(sideEffects) ⇒
      sideEffects(state.state)
      Behaviors.same

    case _ ⇒
      throw new IllegalArgumentException(s"Not supported effect detected [${effect.getClass.getName}]!")
  }

}

//@InternalApi
//class EventsourcedRunning[Command, Event, State](
//  val setup: EventsourcedSetup[Command, Event, State],
//  // internalStash: StashBuffer[Any], // FIXME separate or in settings?
//
//  private var sequenceNr: Long,
//  val writerIdentity:     WriterIdentity,
//
//  private var state: State
//) extends MutableBehavior[Any]
//  with EventsourcedBehavior[Command, Event, State]
//  with EventsourcedStashManagement { same ⇒
//  import setup._
//
//  import EventsourcedBehavior._
//  import akka.actor.typed.scaladsl.adapter._
//
//  // Holds callbacks for persist calls (note that we do not implement persistAsync currently)
//  private def hasNoPendingInvocations: Boolean = pendingInvocations.isEmpty
//  private val pendingInvocations = new java.util.LinkedList[PendingHandlerInvocation]() // we only append / isEmpty / get(0) on it
//
//  // ----------
////
////  private def snapshotSequenceNr: Long = sequenceNr
////
////  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
////    if (persistent.sequenceNr > sequenceNr) sequenceNr = persistent.sequenceNr
////  private def nextSequenceNr(): Long = {
////    sequenceNr += 1L
////    sequenceNr
////  }
//  // ----------
//
//  private def onSnapshotterResponse[C, E, S](setup: EventsourcedSetup[C, E, S], response: SnapshotProtocol.Response): Behavior[C] = {
//    response match {
//      case SaveSnapshotSuccess(meta) ⇒
//        setup.context.log.debug("Save snapshot successful: " + meta)
//        Behaviors.same
//      case SaveSnapshotFailure(meta, ex) ⇒
//        setup.context.log.error(ex, "Save snapshot failed! " + meta)
//        Behaviors.same // FIXME https://github.com/akka/akka/issues/24637 should we provide callback for this? to allow Stop
//    }
//  }
//
//  // ----------
//
//  trait EventsourcedRunningPhase {
//    def name: String
//    def onCommand(c: Command): Behavior[Any]
//    def onJournalResponse(response: JournalProtocol.Response): Behavior[Any]
//  }
//
////  object HandlingCommands extends EventsourcedRunningPhase {
////    def name = "HandlingCommands"
////
////    final override def onCommand(command: Command): Behavior[Any] = {
////      val effect = commandHandler(commandContext, state, command)
////      applyEffects(command, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
////    }
////    final override def onJournalResponse(response: Response): Behavior[Any] = {
////      // ignore, could happen if actor was restarted?
////    }
////  }
//
//  object PersistingEventsNoSideEffects extends PersistingEvents(Nil)
//
//  sealed class PersistingEvents(sideEffects: immutable.Seq[ChainableEffect[_, S]]) extends EventsourcedRunningPhase {
//    def name = "PersistingEvents"
//
//    final override def onCommand(c: Command): Behavior[Any] = {
//      stash(setup, context, c)
//      same
//    }
//
//    final override def onJournalResponse(response: Response): Behavior[Any] = {
//      log.debug("Received Journal response: {}", response)
//      response match {
//        case WriteMessageSuccess(p, id) ⇒
//          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
//          // while message is in flight, in that case we ignore the call to the handler
//          if (id == writerIdentity.instanceId) {
//            updateLastSequenceNr(p)
//            popApplyHandler(p.payload)
//            onWriteMessageComplete()
//            tryUnstash(setup, internalStash, applySideEffects(sideEffects))
//          } else same
//
//        case WriteMessageRejected(p, cause, id) ⇒
//          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
//          // while message is in flight, in that case the handler has already been discarded
//          if (id == writerIdentity.instanceId) {
//            updateLastSequenceNr(p)
//            onPersistRejected(cause, p.payload, p.sequenceNr) // does not stop
//            tryUnstash(setup, applySideEffects(sideEffects))
//          } else same
//
//        case WriteMessageFailure(p, cause, id) ⇒
//          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
//          // while message is in flight, in that case the handler has already been discarded
//          if (id == writerIdentity.instanceId) {
//            onWriteMessageComplete()
//            onPersistFailureThenStop(cause, p.payload, p.sequenceNr)
//          } else same
//
//        case WriteMessagesSuccessful ⇒
//          // ignore
//          same
//
//        case WriteMessagesFailed(_) ⇒
//          // ignore
//          same // it will be stopped by the first WriteMessageFailure message; not applying side effects
//
//        case _: LoopMessageSuccess ⇒
//          // ignore, should never happen as there is no persistAsync in typed
//          same
//      }
//    }
//
//    private def onWriteMessageComplete(): Unit =
//      tryBecomeHandlingCommands()
//
//    private def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
//      log.error(
//        cause,
//        "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
//        event.getClass.getName, seqNr, persistenceId, cause.getMessage)
//    }
//
//    private def onPersistFailureThenStop(cause: Throwable, event: Any, seqNr: Long): Behavior[Any] = {
//      log.error(cause, "Failed to persist event type [{}] with sequence number [{}] for persistenceId [{}].",
//        event.getClass.getName, seqNr, persistenceId)
//
//      // FIXME see #24479 for reconsidering the stopping behaviour
//      Behaviors.stopped
//    }
//
//  }
//
//  // the active phase switches between PersistingEvents and HandlingCommands;
//  // we do this via a var instead of behaviours to keep allocations down as this will be flip/flaping on every Persist effect
//  private[this] var phase: EventsourcedRunningPhase = HandlingCommands
//
//  override def onMessage(msg: Any): Behavior[Any] = {
//    msg match {
//      // TODO explore crazy hashcode hack to make this match quicker...?
//      case SnapshotterResponse(r) ⇒ onSnapshotterResponse(r)
//      case JournalResponse(r)     ⇒ phase.onJournalResponse(r)
//      case command: Command @unchecked ⇒
//        // the above type-check does nothing, since Command is tun
//        // we cast explicitly to fail early in case of type mismatch
//        val c = command.asInstanceOf[Command]
//        phase.onCommand(c)
//    }
//  }
//
//  // ----------
//
//  def applySideEffects(effects: immutable.Seq[ChainableEffect[_, S]]): Behavior[Any] = {
//    var res: Behavior[Any] = same
//    val it = effects.iterator
//
//    // if at least one effect results in a `stop`, we need to stop
//    // manual loop implementation to avoid allocations and multiple scans
//    while (it.hasNext) {
//      val effect = it.next()
//      applySideEffect(effect) match {
//        case _: StoppedBehavior[_] ⇒ res = Behaviors.stopped
//        case _                     ⇒ // nothing to do
//      }
//    }
//
//    res
//  }
//
//  def applySideEffect(effect: ChainableEffect[_, S]): Behavior[Any] = effect match {
//    case _: Stop.type @unchecked ⇒
//      Behaviors.stopped
//
//    case SideEffect(sideEffects) ⇒
//      sideEffects(state)
//      same
//
//    case _ ⇒
//      throw new IllegalArgumentException(s"Not supported effect detected [${effect.getClass.getName}]!")
//  }
//
//  def applyEvent(s: S, event: E): S =
//    eventHandler(s, event)
//
//  @tailrec private def applyEffects(msg: Any, effect: EffectImpl[E, S], sideEffects: immutable.Seq[ChainableEffect[_, S]] = Nil): Behavior[Any] = {
//    if (log.isDebugEnabled)
//      log.debug(s"Handled command [{}], resulting effect: [{}], side effects: [{}]", msg.getClass.getName, effect, sideEffects.size)
//
//    effect match {
//      case CompositeEffect(e, currentSideEffects) ⇒
//        // unwrap and accumulate effects
//        applyEffects(msg, e, currentSideEffects ++ sideEffects)
//
//      case Persist(event) ⇒
//        // apply the event before persist so that validation exception is handled before persisting
//        // the invalid event, in case such validation is implemented in the event handler.
//        // also, ensure that there is an event handler for each single event
//        state = applyEvent(state, event)
//        val tags = tagger(event)
//        val eventToPersist = if (tags.isEmpty) event else Tagged(event, tags)
//
//        internalPersist(eventToPersist, sideEffects) { _ ⇒
//          if (snapshotWhen(state, event, sequenceNr))
//            internalSaveSnapshot(state)
//        }
//
//      case PersistAll(events) ⇒
//        if (events.nonEmpty) {
//          // apply the event before persist so that validation exception is handled before persisting
//          // the invalid event, in case such validation is implemented in the event handler.
//          // also, ensure that there is an event handler for each single event
//          var count = events.size
//          var seqNr = sequenceNr
//          val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, false)) {
//            case ((currentState, snapshot), event) ⇒
//              seqNr += 1
//              val shouldSnapshot = snapshot || snapshotWhen(currentState, event, seqNr)
//              (applyEvent(currentState, event), shouldSnapshot)
//          }
//          state = newState
//          val eventsToPersist = events.map { event ⇒
//            val tags = tagger(event)
//            if (tags.isEmpty) event else Tagged(event, tags)
//          }
//
//          internalPersistAll(eventsToPersist, sideEffects) { _ ⇒
//            count -= 1
//            if (count == 0) {
//              sideEffects.foreach(applySideEffect)
//              if (shouldSnapshotAfterPersist)
//                internalSaveSnapshot(state)
//            }
//          }
//        } else {
//          // run side-effects even when no events are emitted
//          tryUnstash(context, applySideEffects(sideEffects))
//        }
//
//      case e: PersistNothing.type @unchecked ⇒
//        tryUnstash(context, applySideEffects(sideEffects))
//
//      case _: Unhandled.type @unchecked ⇒
//        applySideEffects(sideEffects)
//        Behavior.unhandled
//
//      case c: ChainableEffect[_, S] ⇒
//        applySideEffect(c)
//    }
//  }
//
//  private def popApplyHandler(payload: Any): Unit =
//    pendingInvocations.pop().handler(payload)
//
//  private def becomePersistingEvents(sideEffects: immutable.Seq[ChainableEffect[_, S]]): Behavior[Any] = {
//    if (phase.isInstanceOf[PersistingEvents]) throw new IllegalArgumentException(
//      "Attempted to become PersistingEvents while already in this phase! Logic error?")
//
//    phase =
//      if (sideEffects.isEmpty) PersistingEventsNoSideEffects
//      else new PersistingEvents(sideEffects)
//
//    same
//  }
//
//  private def tryBecomeHandlingCommands(): Behavior[Any] = {
//    if (phase == HandlingCommands) throw new IllegalArgumentException(
//      "Attempted to become HandlingCommands while already in this phase! Logic error?")
//
//    if (hasNoPendingInvocations) { // CAN THIS EVER NOT HAPPEN?
//      phase = HandlingCommands
//    }
//
//    same
//  }
//
//  override def toString = s"EventsourcedRunning($persistenceId,${phase.name})"
//}
