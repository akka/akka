/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate.internal

import akka.actor.typed
import akka.actor.typed.internal.ActorContextImpl
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ BackoffSupervisorStrategy, Behavior, Signal, SupervisorStrategy }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.dstate.scaladsl.DurableStateBehavior
import akka.persistence.typed.dstate.{ DeletionCompleted, DeletionFailed, PersistenceCompleted, PersistenceFailed }
import akka.persistence.typed.internal.{ InternalProtocol, JournalFailureException }
import akka.util.unused

@InternalApi
private[akka] final case class DurableStateBehaviorImpl[Command, State](
    persistenceId: PersistenceId,
    emptyState: State,
    commandHandler: DurableStateBehavior.CommandHandler[Command, State],
    loggerClass: Class[_],
    supervisionStrategy: SupervisorStrategy = SupervisorStrategy.stop,
    override val signalHandler: PartialFunction[(State, Signal), Unit] = PartialFunction.empty)
    extends DurableStateBehavior[Command, State] {

  override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] = {

    val ctx = context.asScala
    val hasCustomLoggerName = ctx match {
      case internalCtx: ActorContextImpl[_] => internalCtx.hasCustomLoggerName
      case _                                => false
    }
    if (!hasCustomLoggerName) ctx.setLoggerName(loggerClass)

    val actualSignalHandler: PartialFunction[(State, Signal), Unit] = signalHandler.orElse {
      // default signal handler is always the fallback
      case (_, PersistenceCompleted) =>
        ctx.log.debug("Successfully saved actor state")
      case (_, PersistenceFailed(failure)) =>
        ctx.log.error(s"Failed to save actor state, due to: ${failure.getMessage}")
      case (_, DeletionCompleted) =>
        ctx.log.debug("Successfully deleted actor state")
      case (_, DeletionFailed(failure)) =>
        ctx.log.error(s"Failed to delete actor state, due to: ${failure.getMessage}")
    }

    Behaviors
      .supervise {
        Behaviors.setup[Command] { _ =>
          val setup = new DurableStateBehaviorSetup(
            ctx.asInstanceOf[ActorContext[InternalProtocol]],
            persistenceId,
            emptyState,
            commandHandler,
            actualSignalHandler)

          // TODO: create the behaviors for each phase (similar to EventSourcingBehavior)
          // * It must start in RequestingRecoveryPermit
          // * LoadingState - to read whatever is on db
          // * Running - switch between saving and handling commands
          Behaviors.empty[Command]
        }

      }
      .onFailure[JournalFailureException](supervisionStrategy)
  }

  @InternalStableApi
  private[akka] def initialize(@unused context: ActorContext[_]): Unit = ()

  /**
   * Allows the durable state behavior to react on signals.
   *
   * The regular lifecycle signals can be handled as well as
   * Akka Persistence specific signals (recovery related). Those are all subtypes of
   * [[akka.persistence.typed.dstate.DurableStateSignal]]
   */
  override def receiveSignal(
      signalHandler: PartialFunction[(State, Signal), Unit]): DurableStateBehavior[Command, State] =
    copy(signalHandler = signalHandler)

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  override def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): DurableStateBehavior[Command, State] =
    copy(supervisionStrategy = backoffStrategy)
}
