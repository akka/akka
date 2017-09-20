/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.persistence.scaladsl

import akka.typed
import akka.typed.scaladsl.ActorContext
import akka.typed.ExtensibleBehavior
import akka.typed.Signal
import akka.typed.Behavior
import scala.reflect.ClassTag
import akka.typed.Behavior.UntypedBehavior

object PersistentActor {
  def persistent[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: ActionHandler[Command, Event, State],
    onEvent:        (Event, State) ⇒ State): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior

  sealed abstract class PersistentEffect[+Event, State]() {
    def andThen(callback: State ⇒ Unit): PersistentEffect[Event, State]
  }

  final case class PersistNothing[Event, State](callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
    def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
  }

  case class Persist[Event, State](event: Event, callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
    def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
  }

  case class Unhandled[Event, State](callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
    def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
  }

  class ActionHandler[Command: ClassTag, Event, State](val handler: ((Any, State, ActorContext[Command]) ⇒ PersistentEffect[Event, State])) {
    def onSignal(signalHandler: PartialFunction[(Any, State, ActorContext[Command]), PersistentEffect[Event, State]]): ActionHandler[Command, Event, State] =
      ActionHandler {
        case (command: Command, state, ctx) ⇒ handler(command, state, ctx)
        case (signal: Signal, state, ctx)   ⇒ signalHandler.orElse(unhandledSignal).apply((signal, state, ctx))
        case _                              ⇒ Unhandled()
      }
    private val unhandledSignal: PartialFunction[(Any, State, ActorContext[Command]), PersistentEffect[Event, State]] = { case _ ⇒ Unhandled() }
  }
  object ActionHandler {
    def cmd[Command: ClassTag, Event, State](commandHandler: Command ⇒ PersistentEffect[Event, State]): ActionHandler[Command, Event, State] = ???
    def apply[Command: ClassTag, Event, State](commandHandler: ((Command, State, ActorContext[Command]) ⇒ PersistentEffect[Event, State])): ActionHandler[Command, Event, State] =
      new ActionHandler(commandHandler.asInstanceOf[((Any, State, ActorContext[Command]) ⇒ PersistentEffect[Event, State])])
    def byState[Command: ClassTag, Event, State](actionHandler: State ⇒ ActionHandler[Command, Event, State]): ActionHandler[Command, Event, State] =
      new ActionHandler(handler = {
        case (action, state, ctx) ⇒ actionHandler(state).handler(action, state, ctx)
      })
  }
}

class PersistentBehavior[Command, Event, State] extends ExtensibleBehavior[Command] {
  override def receiveSignal(ctx: typed.ActorContext[Command], msg: Signal): Behavior[Command] = ???
  override def receiveMessage(ctx: typed.ActorContext[Command], msg: Command): Behavior[Command] = ???

  def onRecoveryComplete(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State] = ???
  def snapshotOnState(predicate: State ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???
  def snapshotOn(predicate: (State, Event) ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???
}
