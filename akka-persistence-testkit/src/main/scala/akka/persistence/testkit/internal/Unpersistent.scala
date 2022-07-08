/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import akka.actor.typed.Behavior
import akka.actor.typed.internal.BehaviorImpl.DeferredBehavior
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import akka.persistence.testkit.{ ChangePersisted, EventPersisted, StatePersisted }
import akka.persistence.typed.internal.EventSourcedBehaviorImpl
import akka.persistence.typed.state.internal.DurableStateBehaviorImpl

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.mutable.ListBuffer

import java.util.concurrent.ConcurrentLinkedQueue

object Unpersistent {

  /** Given an EventSourcedBehavior, converts it to a non-persistent Behavior which synchronously publishes events
   *  and snapshots for inspection.  State is updated as in the EventSourcedBehavior, and side effects are performed
   *  synchronously.  The resulting Behavior is, contingent on the command handling, event handling, and side effects
   *  being compatible with the BehaviorTestKit, testable with the BehaviorTestKit.
   *
   *  Unlike the EventSourcedBehaviorTestKit, this style of testing does not depend on configuration: it therefore does
   *  not serialize events and it assumes an unbounded stash for commands.  It should, however, execute tests
   *  substantially more quickly than the EventSourcedBehaviorTestKit and thus may be more suitable for short cycles of
   *  changes followed by spec validation or performing property-based tests on a Behavior.
   */
  def eventSourced[Command, Event, State](behavior: Behavior[Command], fromStateAndOffset: Option[(State, Long)])
      : (Behavior[Command], ConcurrentLinkedQueue[ChangePersisted[State, Event]]) = {
    var esBehavior: EventSourcedBehaviorImpl[Command, Event, State] = null
    val changeQueue = new ConcurrentLinkedQueue[ChangePersisted[State, Event]]()

    @tailrec
    def findEventSourcedBehavior(b: Behavior[Command], context: ActorContext[Command]): Unit = {
      b match {
        case es: EventSourcedBehaviorImpl[Command, _, _] =>
          esBehavior = es.asInstanceOf[EventSourcedBehaviorImpl[Command, Event, State]]

        case deferred: DeferredBehavior[Command] =>
          findEventSourcedBehavior(deferred(context), context)

        case _ => ()
      }
    }

    val retBehavior =
      Behaviors.setup[Command] { context =>
        findEventSourcedBehavior(behavior, context)

        if (esBehavior == null) {
          context.log.warn("Did not find the expected EventSourcedBehavior")
          Behaviors.empty
        } else {
          val (initialState, initialOffset) = fromStateAndOffset.getOrElse(esBehavior.emptyState -> 0L)
          new WrappedEventSourcedBehavior(context, esBehavior, changeQueue, initialState, initialOffset)
        }
      }

    (retBehavior, changeQueue)
  }

  def durableState[Command, State](
      behavior: Behavior[Command],
      fromState: Option[State]): (Behavior[Command], ConcurrentLinkedQueue[ChangePersisted[State, Nothing]]) = {

    var dsBehavior: DurableStateBehaviorImpl[Command, State] = null
    val changeQueue = new ConcurrentLinkedQueue[ChangePersisted[State, Nothing]]()

    @tailrec
    def findDurableStateBehavior(b: Behavior[Command], context: ActorContext[Command]): Unit =
      b match {
        case ds: DurableStateBehaviorImpl[Command, _] =>
          dsBehavior = ds.asInstanceOf[DurableStateBehaviorImpl[Command, State]]

        case deferred: DeferredBehavior[Command] => findDurableStateBehavior(deferred(context), context)
        case _                                   => ()
      }

    val retBehavior =
      Behaviors.setup[Command] { context =>
        findDurableStateBehavior(behavior, context)

        if (dsBehavior == null) {
          context.log.warn("Did not find the expected DurableStateBehavior")
          Behaviors.empty
        } else {
          val initialState = fromState.getOrElse(dsBehavior.emptyState)
          new WrappedDurableStateBehavior(context, dsBehavior, changeQueue, initialState)
        }
      }

    (retBehavior, changeQueue)
  }

  val doNothing: Any => Unit = _ => ()

  private class WrappedEventSourcedBehavior[Command, Event, State](
      context: ActorContext[Command],
      esBehavior: EventSourcedBehaviorImpl[Command, Event, State],
      changeQueue: ConcurrentLinkedQueue[ChangePersisted[State, Event]],
      initialState: State,
      initialOffset: Long)
      extends AbstractBehavior[Command](context) {
    import akka.persistence.typed.{ EventSourcedSignal, RecoveryCompleted, SnapshotCompleted, SnapshotMetadata }
    import akka.persistence.typed.internal._

    private def commandHandler = esBehavior.commandHandler
    private def eventHandler = esBehavior.eventHandler
    private def tagger = esBehavior.tagger
    private def snapshotWhen = esBehavior.snapshotWhen
    private def retention = esBehavior.retention
    private def signalHandler = esBehavior.signalHandler

    private var offset: Long = initialOffset
    private var state: State = initialState
    private val stashedCommands = ListBuffer.empty[Command]

    private def snapshotMetadata() =
      SnapshotMetadata(esBehavior.persistenceId.toString, offset, System.currentTimeMillis())
    private def sendSignal(signal: EventSourcedSignal): Unit =
      signalHandler.applyOrElse(state -> signal, doNothing)

    sendSignal(RecoveryCompleted)

    override def onMessage(cmd: Command): Behavior[Command] = {
      var shouldSnapshot = false
      var shouldUnstash = false
      var shouldStop = false

      def snapshotRequested(evt: Event): Boolean = {
        val snapshotFromRetention = retention match {
          case DisabledRetentionCriteria             => false
          case s: SnapshotCountRetentionCriteriaImpl => s.snapshotWhen(offset)
          case unexpected                            => throw new IllegalStateException(s"Unexpected retention criteria: $unexpected")
        }

        snapshotFromRetention || snapshotWhen(state, evt, offset)
      }

      def persistEvent(evt: Event): Unit = {
        offset += 1
        changeQueue.offer(EventPersisted(evt, offset, tagger(evt)))
        state = eventHandler(state, evt)
        shouldSnapshot = shouldSnapshot || snapshotRequested(evt)
      }

      @tailrec
      def applyEffects(curEffect: EffectImpl[Event, State], sideEffects: immutable.Seq[SideEffect[State]]): Unit =
        curEffect match {
          case CompositeEffect(eff: EffectImpl[Event, State], se) =>
            applyEffects(eff, se ++ sideEffects)

          case Persist(event) =>
            persistEvent(event)
            sideEffect(sideEffects)

          case PersistAll(events) =>
            events.foreach(persistEvent)
            sideEffect(sideEffects)

          // From outside of the behavior, these are equivalent: no state update
          case _: PersistNothing.type | _: Unhandled.type =>
            sideEffect(sideEffects)

          case _: Stash.type =>
            stashedCommands.append(cmd)
            sideEffect(sideEffects)

          case _ =>
            context.log.error("Unexpected effect {}, stopping", curEffect)
            Behaviors.stopped
        }

      def sideEffect(sideEffects: immutable.Seq[SideEffect[State]]): Unit =
        sideEffects.iterator.foreach { effect =>
          effect match {
            case _: Stop.type       => shouldStop = true
            case _: UnstashAll.type => shouldUnstash = true
            case cb: Callback[_]    => cb.sideEffect(state)
          }
        }

      applyEffects(commandHandler(state, cmd).asInstanceOf[EffectImpl[Event, State]], Nil)

      if (shouldSnapshot) {
        changeQueue.offer(StatePersisted(state, offset, Set.empty))
        sendSignal(SnapshotCompleted(snapshotMetadata()))
      }

      if (shouldStop) Behaviors.stopped
      else if (shouldUnstash && stashedCommands.nonEmpty) {
        val numStashed = stashedCommands.length
        val thisWrappedBehavior = this

        Behaviors.setup { _ =>
          Behaviors.withStash(numStashed) { stash =>
            stashedCommands.foreach { sc =>
              stash.stash(sc)
              ()
            }

            stashedCommands.remove(0, numStashed)
            stash.unstashAll(thisWrappedBehavior)
          }
        }
      } else this
    }
  }

  private class WrappedDurableStateBehavior[Command, State](
      context: ActorContext[Command],
      dsBehavior: DurableStateBehaviorImpl[Command, State],
      changeQueue: ConcurrentLinkedQueue[ChangePersisted[State, Nothing]],
      initialState: State)
      extends AbstractBehavior[Command](context) {

    import akka.persistence.typed.state.{ DurableStateSignal, RecoveryCompleted }
    import akka.persistence.typed.state.internal._

    private def commandHandler = dsBehavior.commandHandler
    private def signalHandler = dsBehavior.signalHandler
    private val tags = Set(dsBehavior.tag)

    private var offset: Long = 0
    private var state: State = initialState
    private val stashedCommands = ListBuffer.empty[Command]

    private def sendSignal(signal: DurableStateSignal): Unit =
      signalHandler.applyOrElse(state -> signal, doNothing)

    sendSignal(RecoveryCompleted)

    override def onMessage(cmd: Command): Behavior[Command] = {
      var shouldUnstash = false
      var shouldStop = false

      def persistState(st: State): Unit = {
        offset += 1
        changeQueue.offer(StatePersisted(st, offset, tags))
        state = st
      }

      @tailrec
      def applyEffects(curEffect: EffectImpl[State], sideEffects: immutable.Seq[SideEffect[State]]): Unit =
        curEffect match {
          case CompositeEffect(eff: EffectImpl[_], se) =>
            applyEffects(eff.asInstanceOf[EffectImpl[State]], se ++ sideEffects)

          case Persist(st) =>
            persistState(st)
            sideEffect(sideEffects)

          case _: PersistNothing.type | _: Unhandled.type =>
            sideEffect(sideEffects)

          case _: Stash.type =>
            stashedCommands.append(cmd)
            sideEffect(sideEffects)

          case _ =>
            context.log.error("Unexpected effect, stopping")
            Behaviors.stopped
        }

      def sideEffect(sideEffects: immutable.Seq[SideEffect[State]]): Unit =
        sideEffects.iterator.foreach { effect =>
          effect match {
            case _: Stop.type       => shouldStop = true
            case _: UnstashAll.type => shouldUnstash = true
            case cb: Callback[_]    => cb.sideEffect(state)
          }
        }

      applyEffects(commandHandler(state, cmd).asInstanceOf[EffectImpl[State]], Nil)

      if (shouldStop) Behaviors.stopped
      else if (shouldUnstash && stashedCommands.nonEmpty) {
        val numStashed = stashedCommands.length
        val thisWrappedBehavior = this

        Behaviors.setup { _ =>
          Behaviors.withStash(numStashed) { stash =>
            stashedCommands.foreach { sc =>
              stash.stash(sc)
              () // explicit discard
            }
            stashedCommands.remove(0, numStashed)
            stash.unstashAll(thisWrappedBehavior)
          }
        }
      } else this
    }
  }
}
