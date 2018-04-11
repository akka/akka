/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.MutableBehavior
import akka.annotation.InternalApi
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.journal.Tagged
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, MDC }
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol._

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * INTERNAL API
 *
 * Conceptually fourth (of four) -- also known as 'final' or 'ultimate' -- form of PersistentBehavior.
 *
 * In this phase recovery has completed successfully and we continue handling incoming commands,
 * as well as persisting new events as dictated by the user handlers.
 *
 * This behavior operates in two phases (also behaviors):
 * - HandlingCommands - where the command handler is invoked for incoming commands
 * - PersistingEvents - where incoming commands are stashed until persistence completes
 *
 * This is implemented as such to avoid creating many EventsourcedRunning instances,
 * which perform the Persistence extension lookup on creation and similar things (config lookup)
 *
 * See previous [[EventsourcedReplayingEvents]].
 */
@InternalApi
private[akka] object EventsourcedRunning {

  final case class EventsourcedState[State](
    seqNr: Long,
    state: State
  ) {

    def nextSequenceNr(): EventsourcedState[State] =
      copy(seqNr = seqNr + 1)

    def updateLastSequenceNr(persistent: PersistentRepr): EventsourcedState[State] =
      if (persistent.sequenceNr > seqNr) copy(seqNr = persistent.sequenceNr) else this

    def applyEvent[C, E](setup: EventsourcedSetup[C, E, State], event: E): EventsourcedState[State] = {
      val updated = setup.eventHandler(state, event)
      copy(state = updated)
    }
  }

  def apply[C, E, S](setup: EventsourcedSetup[C, E, S], state: EventsourcedState[S]): Behavior[InternalProtocol] =
    new EventsourcedRunning(setup.setMdc(MDC.RunningCmds)).handlingCommands(state)
}

// ===============================================

/** INTERNAL API */
@InternalApi private[akka] class EventsourcedRunning[C, E, S](
  override val setup: EventsourcedSetup[C, E, S])
  extends EventsourcedJournalInteractions[C, E, S] with EventsourcedStashManagement[C, E, S] {
  import EventsourcedRunning.EventsourcedState

  private def commandContext = setup.commandContext

  private val runningCmdsMdc = MDC.create(setup.persistenceId, MDC.RunningCmds)
  private val persistingEventsMdc = MDC.create(setup.persistenceId, MDC.PersistingEvents)

  def handlingCommands(state: EventsourcedState[S]): Behavior[InternalProtocol] = {

    def onCommand(state: EventsourcedState[S], cmd: C): Behavior[InternalProtocol] = {
      val effect = setup.commandHandler(commandContext, state.state, cmd)
      applyEffects(cmd, state, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
    }

    @tailrec def applyEffects(
      msg:         Any,
      state:       EventsourcedState[S],
      effect:      EffectImpl[E, S],
      sideEffects: immutable.Seq[ChainableEffect[_, S]] = Nil
    ): Behavior[InternalProtocol] = {
      if (setup.log.isDebugEnabled)
        setup.log.debug(
          s"Handled command [{}], resulting effect: [{}], side effects: [{}]",
          msg.getClass.getName, effect, sideEffects.size)

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

          val shouldSnapshotAfterPersist = setup.snapshotWhen(newState2.state, event, newState2.seqNr)

          persistingEvents(newState2, numberOfEvents = 1, shouldSnapshotAfterPersist, sideEffects)

        case PersistAll(events) ⇒
          if (events.nonEmpty) {
            // apply the event before persist so that validation exception is handled before persisting
            // the invalid event, in case such validation is implemented in the event handler.
            // also, ensure that there is an event handler for each single event
            var seqNr = state.seqNr
            val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, false)) {
              case ((currentState, snapshot), event) ⇒
                seqNr += 1
                val shouldSnapshot = snapshot || setup.snapshotWhen(currentState.state, event, seqNr)
                (currentState.applyEvent(setup, event), shouldSnapshot)
            }

            val eventsToPersist = events.map(tagEvent)

            val newState2 = internalPersistAll(eventsToPersist, newState)

            persistingEvents(newState2, events.size, shouldSnapshotAfterPersist, sideEffects)

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

    def tagEvent(event: E): Any = {
      val tags = setup.tagger(event)
      if (tags.isEmpty) event else Tagged(event, tags)
    }

    setup.setMdc(runningCmdsMdc)

    Behaviors.receiveMessage[EventsourcedBehavior.InternalProtocol] {
      case IncomingCommand(c: C @unchecked) ⇒ onCommand(state, c)
      case SnapshotterResponse(r)           ⇒ onSnapshotterResponse(r, Behaviors.same)
      case _                                ⇒ Behaviors.unhandled
    }

  }

  // ===============================================

  def persistingEvents(
    state:                      EventsourcedState[S],
    numberOfEvents:             Int,
    shouldSnapshotAfterPersist: Boolean,
    sideEffects:                immutable.Seq[ChainableEffect[_, S]]
  ): Behavior[InternalProtocol] = {
    setup.setMdc(persistingEventsMdc)
    new PersistingEvents(state, numberOfEvents, shouldSnapshotAfterPersist, sideEffects)
  }

  class PersistingEvents(
    var state:                  EventsourcedState[S],
    numberOfEvents:             Int,
    shouldSnapshotAfterPersist: Boolean,
    var sideEffects:            immutable.Seq[ChainableEffect[_, S]])
    extends MutableBehavior[EventsourcedBehavior.InternalProtocol] {

    private var eventCounter = 0

    override def onMessage(msg: EventsourcedBehavior.InternalProtocol): Behavior[EventsourcedBehavior.InternalProtocol] = {
      msg match {
        case SnapshotterResponse(r)            ⇒ onSnapshotterResponse(r, this)
        case JournalResponse(r)                ⇒ onJournalResponse(r)
        case in: IncomingCommand[C @unchecked] ⇒ onCommand(in)
        case RecoveryTickEvent(_)              ⇒ Behaviors.unhandled
        case RecoveryPermitGranted             ⇒ Behaviors.unhandled
      }
    }

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      stash(cmd)
      this
    }

    final def onJournalResponse(
      response: Response): Behavior[InternalProtocol] = {
      setup.log.debug("Received Journal response: {}", response)

      def onWriteResponse(p: PersistentRepr): Behavior[InternalProtocol] = {
        state = state.updateLastSequenceNr(p)
        eventCounter += 1

        // only once all things are applied we can revert back
        if (eventCounter < numberOfEvents) this
        else {
          if (shouldSnapshotAfterPersist)
            internalSaveSnapshot(state)

          tryUnstash(applySideEffects(sideEffects, state))
        }
      }

      response match {
        case WriteMessageSuccess(p, id) ⇒
          if (id == setup.writerIdentity.instanceId)
            onWriteResponse(p)
          else this

        case WriteMessageRejected(p, cause, id) ⇒
          if (id == setup.writerIdentity.instanceId) {
            onPersistRejected(cause, p.payload, p.sequenceNr) // does not stop (by design)
            onWriteResponse(p)
          } else this

        case WriteMessageFailure(p, cause, id) ⇒
          if (id == setup.writerIdentity.instanceId)
            onPersistFailureThenStop(cause, p.payload, p.sequenceNr)
          else this

        case WriteMessagesSuccessful ⇒
          // ignore
          this

        case WriteMessagesFailed(_) ⇒
          // ignore
          this // it will be stopped by the first WriteMessageFailure message; not applying side effects

        case _ ⇒
          // ignore all other messages, since they relate to recovery handling which we're not dealing with in Running phase
          Behaviors.unhandled
      }
    }

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

  }

  private def onSnapshotterResponse(
    response: SnapshotProtocol.Response,
    outer:    Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    response match {
      case SaveSnapshotSuccess(meta) ⇒
        setup.context.log.debug("Save snapshot successful, snapshot metadata: [{}]", meta)
        outer
      case SaveSnapshotFailure(meta, ex) ⇒
        setup.context.log.error(ex, "Save snapshot failed, snapshot metadata: [{}]", meta)
        outer // FIXME https://github.com/akka/akka/issues/24637 should we provide callback for this? to allow Stop

      // FIXME not implemented
      case DeleteSnapshotFailure(_, _)  ⇒ ???
      case DeleteSnapshotSuccess(_)     ⇒ ???
      case DeleteSnapshotsFailure(_, _) ⇒ ???
      case DeleteSnapshotsSuccess(_)    ⇒ ???

      // ignore LoadSnapshot messages
      case _ ⇒
        Behaviors.unhandled
    }
  }

  // --------------------------

  def applySideEffects(effects: immutable.Seq[ChainableEffect[_, S]], state: EventsourcedState[S]): Behavior[InternalProtocol] = {
    var res: Behavior[InternalProtocol] = handlingCommands(state)
    val it = effects.iterator

    // if at least one effect results in a `stop`, we need to stop
    // manual loop implementation to avoid allocations and multiple scans
    while (it.hasNext) {
      val effect = it.next()
      val stopped = !Behavior.isAlive(applySideEffect(effect, state))
      if (stopped) res = Behaviors.stopped
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
