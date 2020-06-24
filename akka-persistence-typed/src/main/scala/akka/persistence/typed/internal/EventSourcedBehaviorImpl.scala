/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed
import akka.actor.typed.ActorRef
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.internal.ActorContextImpl
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation._
import akka.persistence.JournalProtocol
import akka.persistence.Recovery
import akka.persistence.RecoveryPermitter
import akka.persistence.SnapshotProtocol
import akka.persistence.typed.DeleteEventsCompleted
import akka.persistence.typed.DeleteEventsFailed
import akka.persistence.typed.DeleteSnapshotsCompleted
import akka.persistence.typed.DeleteSnapshotsFailed
import akka.persistence.typed.DeletionTarget
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.NoOpEventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.SnapshotAdapter
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.SnapshotSelectionCriteria
import akka.persistence.typed.scaladsl.EventSourcedBehavior.ActiveActive
import akka.persistence.typed.scaladsl._
import akka.persistence.typed.scaladsl.{ Recovery => TypedRecovery }
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.util.ConstantFun
import akka.util.unused

@InternalApi
private[akka] object EventSourcedBehaviorImpl {

  object WriterIdentity {

    // ok to wrap around (2*Int.MaxValue restarts will not happen within a journal roundtrip)
    private[akka] val instanceIdCounter = new AtomicInteger(1)

    def newIdentity(): WriterIdentity = {
      val instanceId: Int = WriterIdentity.instanceIdCounter.getAndIncrement()
      val writerUuid: String = UUID.randomUUID.toString
      WriterIdentity(instanceId, writerUuid)
    }
  }
  final case class WriterIdentity(instanceId: Int, writerUuid: String)

  /**
   * Used by EventSourcedBehaviorTestKit to retrieve the `persistenceId`.
   */
  final case class GetPersistenceId(replyTo: ActorRef[PersistenceId]) extends Signal

  /**
   * Used by EventSourcedBehaviorTestKit to retrieve the state.
   * Can't be a Signal because those are not stashed.
   */
  final case class GetState[State](replyTo: ActorRef[State]) extends InternalProtocol

}

@InternalApi
private[akka] final case class EventSourcedBehaviorImpl[Command, Event, State](
    persistenceId: PersistenceId,
    emptyState: State,
    commandHandler: EventSourcedBehavior.CommandHandler[Command, Event, State],
    eventHandler: EventSourcedBehavior.EventHandler[State, Event],
    loggerClass: Class[_],
    journalPluginId: Option[String] = None,
    snapshotPluginId: Option[String] = None,
    tagger: Event => Set[String] = (_: Event) => Set.empty[String],
    eventAdapter: EventAdapter[Event, Any] = NoOpEventAdapter.instance[Event],
    snapshotAdapter: SnapshotAdapter[State] = NoOpSnapshotAdapter.instance[State],
    snapshotWhen: (State, Event, Long) => Boolean = ConstantFun.scalaAnyThreeToFalse,
    recovery: Recovery = Recovery(),
    retention: RetentionCriteria = RetentionCriteria.disabled,
    supervisionStrategy: SupervisorStrategy = SupervisorStrategy.stop,
    override val signalHandler: PartialFunction[(State, Signal), Unit] = PartialFunction.empty,
    activeActive: Option[ActiveActive] = None)
    extends EventSourcedBehavior[Command, Event, State] {

  import EventSourcedBehaviorImpl.WriterIdentity

  if (persistenceId eq null)
    throw new IllegalArgumentException("persistenceId must not be null")

  override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] = {
    val ctx = context.asScala
    val hasCustomLoggerName = ctx match {
      case internalCtx: ActorContextImpl[_] => internalCtx.hasCustomLoggerName
      case _                                => false
    }
    if (!hasCustomLoggerName) ctx.setLoggerName(loggerClass)
    val settings = EventSourcedSettings(ctx.system, journalPluginId.getOrElse(""), snapshotPluginId.getOrElse(""))

    // stashState outside supervise because StashState should survive restarts due to persist failures
    val stashState = new StashState(ctx.asInstanceOf[ActorContext[InternalProtocol]], settings)

    val actualSignalHandler: PartialFunction[(State, Signal), Unit] = signalHandler.orElse {
      // default signal handler is always the fallback
      case (_, SnapshotCompleted(meta)) =>
        ctx.log.debug("Save snapshot successful, snapshot metadata [{}].", meta)
      case (_, SnapshotFailed(meta, failure)) =>
        ctx.log.error(s"Save snapshot failed, snapshot metadata [$meta] due to: ${failure.getMessage}", failure)
      case (_, DeleteSnapshotsCompleted(DeletionTarget.Individual(meta))) =>
        ctx.log.debug("Persistent snapshot [{}] deleted successfully.", meta)
      case (_, DeleteSnapshotsCompleted(DeletionTarget.Criteria(criteria))) =>
        ctx.log.debug("Persistent snapshots given criteria [{}] deleted successfully.", criteria)
      case (_, DeleteSnapshotsFailed(DeletionTarget.Individual(meta), failure)) =>
        ctx.log.warn2("Failed to delete snapshot with meta [{}] due to: {}", meta, failure.getMessage)
      case (_, DeleteSnapshotsFailed(DeletionTarget.Criteria(criteria), failure)) =>
        ctx.log.warn2("Failed to delete snapshots given criteria [{}] due to: {}", criteria, failure.getMessage)
      case (_, DeleteEventsCompleted(toSequenceNr)) =>
        ctx.log.debug("Events successfully deleted to sequence number [{}].", toSequenceNr)
      case (_, DeleteEventsFailed(toSequenceNr, failure)) =>
        ctx.log.warn2("Failed to delete events to sequence number [{}] due to: {}", toSequenceNr, failure.getMessage)
      case (_, EventSourcedBehaviorImpl.GetPersistenceId(replyTo)) => replyTo ! persistenceId
    }

    // do this once, even if the actor is restarted
    initialize(context.asScala)

    Behaviors
      .supervise {
        Behaviors.setup[Command] { _ =>
          val eventSourcedSetup = new BehaviorSetup(
            ctx.asInstanceOf[ActorContext[InternalProtocol]],
            persistenceId,
            emptyState,
            commandHandler,
            eventHandler,
            WriterIdentity.newIdentity(),
            actualSignalHandler,
            tagger,
            eventAdapter,
            snapshotAdapter,
            snapshotWhen,
            recovery,
            retention,
            holdingRecoveryPermit = false,
            settings = settings,
            stashState = stashState,
            activeActive = activeActive)

          // needs to accept Any since we also can get messages from the journal
          // not part of the user facing Command protocol
          def interceptor: BehaviorInterceptor[Any, InternalProtocol] = new BehaviorInterceptor[Any, InternalProtocol] {

            import BehaviorInterceptor._
            override def aroundReceive(
                ctx: typed.TypedActorContext[Any],
                msg: Any,
                target: ReceiveTarget[InternalProtocol]): Behavior[InternalProtocol] = {
              val innerMsg = msg match {
                case res: JournalProtocol.Response           => InternalProtocol.JournalResponse(res)
                case res: SnapshotProtocol.Response          => InternalProtocol.SnapshotterResponse(res)
                case RecoveryPermitter.RecoveryPermitGranted => InternalProtocol.RecoveryPermitGranted
                case internal: InternalProtocol              => internal // such as RecoveryTickEvent
                case cmd: Command @unchecked                 => InternalProtocol.IncomingCommand(cmd)
              }
              target(ctx, innerMsg)
            }

            override def aroundSignal(
                ctx: typed.TypedActorContext[Any],
                signal: Signal,
                target: SignalTarget[InternalProtocol]): Behavior[InternalProtocol] = {
              if (signal == PostStop) {
                eventSourcedSetup.cancelRecoveryTimer()
                // clear stash to be GC friendly
                stashState.clearStashBuffers()
              }
              target(ctx, signal)
            }

            override def toString: String = "EventSourcedBehaviorInterceptor"
          }

          Behaviors.intercept(() => interceptor)(RequestingRecoveryPermit(eventSourcedSetup)).narrow
        }

      }
      .onFailure[JournalFailureException](supervisionStrategy)
  }

  @InternalStableApi
  private[akka] def initialize(@unused context: ActorContext[_]): Unit = ()

  override def receiveSignal(
      handler: PartialFunction[(State, Signal), Unit]): EventSourcedBehavior[Command, Event, State] =
    copy(signalHandler = handler)

  override def withJournalPluginId(id: String): EventSourcedBehavior[Command, Event, State] = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = if (id != "") Some(id) else None)
  }

  override def withSnapshotPluginId(id: String): EventSourcedBehavior[Command, Event, State] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(snapshotPluginId = if (id != "") Some(id) else None)
  }

  override def withSnapshotSelectionCriteria(
      selection: SnapshotSelectionCriteria): EventSourcedBehavior[Command, Event, State] = {
    copy(recovery = Recovery(selection.toClassic))
  }

  override def snapshotWhen(predicate: (State, Event, Long) => Boolean): EventSourcedBehavior[Command, Event, State] =
    copy(snapshotWhen = predicate)

  override def withRetention(criteria: RetentionCriteria): EventSourcedBehavior[Command, Event, State] =
    copy(retention = criteria)

  override def withTagger(tagger: Event => Set[String]): EventSourcedBehavior[Command, Event, State] =
    copy(tagger = tagger)

  override def eventAdapter(adapter: EventAdapter[Event, _]): EventSourcedBehavior[Command, Event, State] =
    copy(eventAdapter = adapter.asInstanceOf[EventAdapter[Event, Any]])

  override def snapshotAdapter(adapter: SnapshotAdapter[State]): EventSourcedBehavior[Command, Event, State] =
    copy(snapshotAdapter = adapter)

  override def onPersistFailure(
      backoffStrategy: BackoffSupervisorStrategy): EventSourcedBehavior[Command, Event, State] =
    copy(supervisionStrategy = backoffStrategy)

  override def withRecovery(recovery: TypedRecovery): EventSourcedBehavior[Command, Event, State] = {
    copy(recovery = recovery.toClassic)
  }

  override private[akka] def withActiveActive(
      context: ActiveActiveContext,
      id: String,
      allIds: Set[String],
      queryPluginId: String): EventSourcedBehavior[Command, Event, State] = {
    copy(activeActive = Some(ActiveActive(id, allIds, context, queryPluginId)))
  }
}

/** Protocol used internally by the eventsourced behaviors. */
@InternalApi private[akka] sealed trait InternalProtocol
@InternalApi private[akka] object InternalProtocol {
  case object RecoveryPermitGranted extends InternalProtocol
  final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends InternalProtocol
  final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends InternalProtocol
  final case class RecoveryTickEvent(snapshot: Boolean) extends InternalProtocol
  final case class IncomingCommand[C](c: C) extends InternalProtocol

  final case class ReplicatedEventEnvelope[E](event: ReplicatedEvent[E], ack: ActorRef[ReplicatedEventAck.type])
      extends InternalProtocol
}

// FIXME serializer
@InternalApi
private[akka] final case class ReplicatedEventMetaData(originDc: String)
@InternalApi
private[akka] final case class ReplicatedEvent[E](event: E, originReplica: String, originSequenceNr: Long)
@InternalApi
private[akka] case object ReplicatedEventAck
