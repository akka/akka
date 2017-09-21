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
  case object Stop

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

  override val persistenceId: String = behavior.persistenceId

  private var state: S = behavior.initialState

  private val actions: Actions[C, E, S] = behavior.actions

  private val eventHandler: (E, S) ⇒ S = behavior.onEvent

  private val ctxAdapter = new ActorContextAdapter[C](context)
  private val ctx = ctxAdapter.asScala

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) ⇒
      state = snapshot.asInstanceOf[S]

    case RecoveryCompleted ⇒
      state = behavior.recoveryCompleted(state, ctx)

    case event: E @unchecked ⇒
      state = applyEvent(state, event)
  }

  def applyEvent(s: S, event: E): S =
    eventHandler.apply(event, s)

  private val unhandledSignal: PartialFunction[(Signal, S, ActorContext[C]), PersistentEffect[E, S]] = {
    case sig ⇒ Unhandled()
  }

  override def receiveCommand: Receive = {
    case PersistentActorImpl.Stop ⇒
      context.stop(self)

    case msg ⇒
      try {
        // FIXME sigHandler(state)
        val effect = msg match {
          case a.Terminated(ref) ⇒
            val sig = Terminated(ActorRefAdapter(ref))(null)
            actions.sigHandler(state).applyOrElse((sig, state, ctx), unhandledSignal)
          case a.ReceiveTimeout ⇒
            actions.commandHandler(ctxAdapter.receiveTimeoutMsg, state, ctx)
          // TODO note that PostStop and PreRestart signals are not handled, we wouldn't be able to persist there
          case cmd: C @unchecked ⇒
            // FIXME we could make it more safe by using ClassTag for C
            actions.commandHandler(cmd, state, ctx)
        }

        effect match {
          case Persist(event, callbacks) ⇒
            // apply the event before persist so that validation exception is handled before persisting
            // the invalid event, in case such validation is implemented in the event handler.
            state = applyEvent(state, event)
            persist(event) { _ ⇒
              callbacks.foreach(_.apply(state))
            }
          // FIXME PersistAll
          case PersistNothing(callbacks) ⇒
            callbacks.foreach(_.apply(state))
          case Unhandled(callbacks) ⇒
            super.unhandled(msg)
            callbacks.foreach(_.apply(state))
        }
      } catch {
        case e: MatchError ⇒ throw new IllegalStateException(
          s"Undefined state [${state.getClass.getName}] or handler for [${msg.getClass.getName} " +
            s"in [${behavior.getClass.getName}] with persistenceId [${persistenceId}]")
      }

  }

}

