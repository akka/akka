/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.typed.persistence.internal

import akka.{ actor ⇒ a }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.{ PersistentActor ⇒ UntypedPersistentActor }
import akka.persistence.RecoveryCompleted
import akka.persistence.SnapshotOffer
import akka.typed.Signal
import akka.typed.internal.adapter.ActorContextAdapter
import akka.typed.persistence.scaladsl.PersistentActor
import akka.typed.persistence.scaladsl.PersistentBehavior
import akka.typed.scaladsl.ActorContext
import akka.typed.Terminated
import akka.typed.internal.adapter.ActorRefAdapter

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
  import PersistentActor._

  private val log = Logging(context.system, behavior.getClass)

  override val persistenceId: String = behavior.persistenceIdFromActorName(self.path.name)

  private var state: S = behavior.initialState

  private val actions: Actions[C, E, S] = behavior.actions

  private val eventHandler: (E, S) ⇒ S = behavior.applyEvent

  private val ctxAdapter = new ActorContextAdapter[C](context)
  private val ctx = ctxAdapter.asScala

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) ⇒
      state = snapshot.asInstanceOf[S]

    case RecoveryCompleted ⇒
      state = behavior.recoveryCompleted(ctx, state)

    case event: E @unchecked ⇒
      state = applyEvent(state, event)
  }

  def applyEvent(s: S, event: E): S =
    eventHandler.apply(event, s)

  private val unhandledSignal: PartialFunction[(ActorContext[C], Signal, S), Effect[E, S]] = {
    case sig ⇒ Unhandled()
  }

  override def receiveCommand: Receive = {
    case PersistentActorImpl.StopForPassivation ⇒
      context.stop(self)

    case msg ⇒
      try {
        val effects = msg match {
          case a.Terminated(ref) ⇒
            val sig = Terminated(ActorRefAdapter(ref))(null)
            actions.sigHandler(state).applyOrElse((ctx, sig, state), unhandledSignal)
          case a.ReceiveTimeout ⇒
            actions.commandHandler(ctx, ctxAdapter.receiveTimeoutMsg, state)
          // TODO note that PostStop and PreRestart signals are not handled, we wouldn't be able to persist there
          case cmd: C @unchecked ⇒
            // FIXME we could make it more safe by using ClassTag for C
            actions.commandHandler(ctx, cmd, state)
        }

        applyEffects(msg, effects)
      } catch {
        case e: MatchError ⇒ throw new IllegalStateException(
          s"Undefined state [${state.getClass.getName}] or handler for [${msg.getClass.getName} " +
            s"in [${behavior.getClass.getName}] with persistenceId [${persistenceId}]")
      }

  }

  private def applyEffects(msg: Any, effect: Effect[E, S], sideEffects: Seq[ChainableEffect[_, S]] = Nil): Unit = effect match {
    case CompositeEffect(Some(persist), sideEffects) ⇒
      applyEffects(msg, persist, sideEffects)
    case CompositeEffect(_, sideEffects) ⇒
      sideEffects.foreach(applySideEffect)
    case Persist(event) ⇒
      // apply the event before persist so that validation exception is handled before persisting
      // the invalid event, in case such validation is implemented in the event handler.
      state = applyEvent(state, event)
      persist(event) { _ ⇒
        sideEffects.foreach(applySideEffect)
      }
    case PersistAll(events) ⇒
      // apply the event before persist so that validation exception is handled before persisting
      // the invalid event, in case such validation is implemented in the event handler.
      state = events.foldLeft(state)(applyEvent)
      persistAll(scala.collection.immutable.Seq(events)) { _ ⇒
        sideEffects.foreach(applySideEffect)
      }
    case PersistNothing() ⇒
    case Unhandled() ⇒
      super.unhandled(msg)
    case c: ChainableEffect[_, S] ⇒
      applySideEffect(c)
  }

  def applySideEffect(effect: ChainableEffect[_, S]): Unit = effect match {
    case Stop()                ⇒ context.stop(self)
    case SideEffect(callbacks) ⇒ callbacks.apply(state)
  }
}

