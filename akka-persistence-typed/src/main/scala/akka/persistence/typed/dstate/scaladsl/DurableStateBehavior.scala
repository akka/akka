/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate.scaladsl

import akka.actor.typed.internal.BehaviorImpl.DeferredBehavior
import akka.actor.typed.internal.LoggerClass
import akka.actor.typed.{BackoffSupervisorStrategy, Signal}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.dstate.internal.DurableStateBehaviorImpl

object DurableStateBehavior {

  type CommandHandler[Command, State] = (State, Command) => Effect[State]

  private val logPrefixSkipList = classOf[DurableStateBehavior[_, _]].getName :: Nil

  def apply[Command, State](
      persistenceId: PersistenceId,
      emptyState: State,
      commandHandler: CommandHandler[Command, State]): DurableStateBehavior[Command, State] = {
    val loggerClass = LoggerClass.detectLoggerClassFromStack(classOf[DurableStateBehavior[_, _]], logPrefixSkipList)
    DurableStateBehaviorImpl(persistenceId, emptyState, commandHandler, loggerClass)
  }

}

trait DurableStateBehavior[Command, State] extends DeferredBehavior[Command] {

  def persistenceId: PersistenceId

  /**
   * Allows the durable state behavior to react on signals.
   *
   * The regular lifecycle signals can be handled as well as
   * Akka Persistence specific signals (recovery related). Those are all subtypes of
   * [[akka.persistence.typed.dstate.DurableStateSignal]]
   */
  def receiveSignal(signalHandler: PartialFunction[(State, Signal), Unit]): DurableStateBehavior[Command, State]

  /**
   * @return The currently defined signal handler or an empty handler if no custom handler previously defined
   */
  def signalHandler: PartialFunction[(State, Signal), Unit]

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): DurableStateBehavior[Command, State]

}
