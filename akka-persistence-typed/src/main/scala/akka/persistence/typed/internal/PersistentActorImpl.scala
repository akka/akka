/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.internal

import akka.{ actor ⇒ a }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.{ PersistentActor ⇒ UntypedPersistentActor }
import akka.persistence.RecoveryCompleted
import akka.persistence.SnapshotOffer
import akka.actor.typed.Signal
import akka.actor.typed.internal.adapter.ActorContextAdapter
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.Terminated
import akka.actor.typed.internal.adapter.ActorRefAdapter
import akka.persistence.journal.Tagged

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PersistentActorImpl {

  /**
   * Stop the actor for passivation. `PoisonPill` does not work well
   * with persistent actors.
   */
  case object StopForPassivation

  def props[C, E, S](
    behaviorFactory: () ⇒ PersistentBehavior[C, E, S]): a.Props =
    a.Props(new PersistentActorImpl(behaviorFactory()))

}

/**
 * INTERNAL API
 * The `PersistentActor` that runs a `PersistentBehavior`.
 */
@InternalApi private[akka] class PersistentActorImpl[C, E, S](
  behavior: PersistentBehavior[C, E, S]) extends UntypedPersistentActor {

  import PersistentActorImpl._
  import PersistentBehaviors._

  override val persistenceId: String = behavior.persistenceIdFromActorName(self.path.name)

  private var state: S = behavior.initialState

  private val commandHandler: CommandHandler[C, E, S] = behavior.commandHandler

  private val eventHandler: (S, E) ⇒ S = behavior.eventHandler

  private val ctxAdapter = new ActorContextAdapter[C](context)
  private val ctx = ctxAdapter.asScala

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) ⇒
      state = snapshot.asInstanceOf[S]

    case RecoveryCompleted ⇒
      behavior.recoveryCompleted(ctx, state)

    case event: E @unchecked ⇒
      state = applyEvent(state, event)
  }

  def applyEvent(s: S, event: E): S =
    eventHandler.apply(s, event)

  private val unhandledSignal: PartialFunction[(ActorContext[C], S, Signal), Effect[E, S]] = {
    case sig ⇒ Effect.unhandled
  }

  override def receiveCommand: Receive = {
    case PersistentActorImpl.StopForPassivation ⇒
      context.stop(self)

    case msg ⇒
      try {
        val effects = msg match {
          case a.ReceiveTimeout ⇒
            commandHandler(ctx, state, ctxAdapter.receiveTimeoutMsg)
          // TODO note that PostStop, PreRestart and Terminated signals are not handled, we wouldn't be able to persist there
          case cmd: C @unchecked ⇒
            // FIXME we could make it more safe by using ClassTag for C
            commandHandler(ctx, state, cmd)
        }

        applyEffects(msg, effects)
      } catch {
        case e: MatchError ⇒ throw new IllegalStateException(
          s"Undefined state [${state.getClass.getName}] or handler for [${msg.getClass.getName} " +
            s"in [${behavior.getClass.getName}] with persistenceId [$persistenceId]")
      }

  }

  private def applyEffects(msg: Any, effect: Effect[E, S], sideEffects: Seq[ChainableEffect[_, S]] = Nil): Unit = effect match {
    case CompositeEffect(Some(persist), currentSideEffects) ⇒
      applyEffects(msg, persist, currentSideEffects ++ sideEffects)
    case CompositeEffect(_, currentSideEffects) ⇒
      (currentSideEffects ++ sideEffects).foreach(applySideEffect)
    case Persist(event) ⇒
      // apply the event before persist so that validation exception is handled before persisting
      // the invalid event, in case such validation is implemented in the event handler.
      // also, ensure that there is an event handler for each single event
      state = applyEvent(state, event)
      val tags = behavior.tagger(event)
      val eventToPersist = if (tags.isEmpty) event else Tagged(event, tags)
      persist(eventToPersist) { _ ⇒
        sideEffects.foreach(applySideEffect)
      }
    case PersistAll(events) ⇒
      if (events.nonEmpty) {
        // apply the event before persist so that validation exception is handled before persisting
        // the invalid event, in case such validation is implemented in the event handler.
        // also, ensure that there is an event handler for each single event
        var count = events.size
        state = events.foldLeft(state)(applyEvent)
        val eventsToPersist = events.map { event ⇒
          val tags = behavior.tagger(event)
          if (tags.isEmpty) event else Tagged(event, tags)
        }
        persistAll(eventsToPersist) { _ ⇒
          count -= 1
          if (count == 0) sideEffects.foreach(applySideEffect)
        }
      } else {
        // run side-effects even when no events are emitted
        sideEffects.foreach(applySideEffect)
      }
    case _: PersistNothing.type @unchecked ⇒
    case _: Unhandled.type @unchecked ⇒
      super.unhandled(msg)
    case c: ChainableEffect[_, S] ⇒
      applySideEffect(c)
  }

  def applySideEffect(effect: ChainableEffect[_, S]): Unit = effect match {
    case _: Stop.type @unchecked ⇒ context.stop(self)
    case SideEffect(callbacks)   ⇒ callbacks.apply(state)
  }
}

