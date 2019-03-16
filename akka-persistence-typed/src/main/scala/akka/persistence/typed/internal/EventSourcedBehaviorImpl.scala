/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

import akka.actor.typed
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.DeleteMessagesFailed
import akka.persistence.typed.DeleteSnapshotsCompleted
import akka.persistence.typed.DeleteSnapshotsFailed
import akka.persistence.typed.DeletionTarget
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.NoOpEventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RetentionCriteria
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.scaladsl._
import akka.util.ConstantFun

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
    tagger: Event ⇒ Set[String] = (_: Event) ⇒ Set.empty[String],
    eventAdapter: EventAdapter[Event, Any] = NoOpEventAdapter.instance[Event],
    snapshotWhen: (State, Event, Long) ⇒ Boolean = ConstantFun.scalaAnyThreeToFalse,
    recovery: Recovery = Recovery(),
    retention: RetentionCriteria = RetentionCriteria(),
    supervisionStrategy: SupervisorStrategy = SupervisorStrategy.stop,
    override val signalHandler: PartialFunction[Signal, Unit] = PartialFunction.empty)
    extends EventSourcedBehavior[Command, Event, State] {

  import EventSourcedBehaviorImpl.WriterIdentity

  override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] = {
    val ctx = context.asScala
    ctx.setLoggerClass(loggerClass)
    val settings = EventSourcedSettings(ctx.system, journalPluginId.getOrElse(""), snapshotPluginId.getOrElse(""))

    // stashState outside supervise because StashState should survive restarts due to persist failures
    val stashState = new StashState(settings)

    val actualSignalHandler: PartialFunction[Signal, Unit] = signalHandler.orElse {
      // default signal handler is always the fallback
      case SnapshotCompleted(meta: SnapshotMetadata) ⇒
        ctx.log.debug("Save snapshot successful, snapshot metadata [{}]", meta)
      case SnapshotFailed(meta, failure) ⇒
        ctx.log.error(failure, "Save snapshot failed, snapshot metadata [{}]", meta)
      case DeleteSnapshotsCompleted(DeletionTarget.Individual(meta)) =>
        ctx.log.debug(s"Persistent snapshot [{}] deleted successfully.", meta)
      case DeleteSnapshotsCompleted(DeletionTarget.Criteria(criteria)) =>
        ctx.log.debug(s"Persistent snapshots given criteria [{}] deleted successfully.", criteria)
      case DeleteSnapshotsFailed(DeletionTarget.Individual(meta), failure) =>
        ctx.log.warning("Failed to delete snapshot with meta [{}] due to [{}].", meta, failure)
      case DeleteSnapshotsFailed(DeletionTarget.Criteria(criteria), failure) =>
        ctx.log.warning("Failed to delete snapshots given criteria [{}] due to [{}].", criteria, failure)
      case DeleteMessagesFailed(toSequenceNr, failure) =>
        ctx.log.warning("Failed to delete messages toSequenceNr [{}] due to [{}].", toSequenceNr, failure)
    }

    Behaviors
      .supervise {
        Behaviors.setup[Command] { _ ⇒
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
            snapshotWhen,
            recovery,
            retention,
            holdingRecoveryPermit = false,
            settings = settings,
            stashState = stashState)

          // needs to accept Any since we also can get messages from the journal
          // not part of the protocol
          val onStopInterceptor = new BehaviorInterceptor[Any, Any] {

            import BehaviorInterceptor._
            def aroundReceive(ctx: typed.TypedActorContext[Any], msg: Any, target: ReceiveTarget[Any])
                : Behavior[Any] = {
              target(ctx, msg)
            }

            def aroundSignal(ctx: typed.TypedActorContext[Any], signal: Signal, target: SignalTarget[Any])
                : Behavior[Any] = {
              if (signal == PostStop) {
                eventSourcedSetup.cancelRecoveryTimer()
                // clear stash to be GC friendly
                stashState.clearStashBuffers()
              }
              val nextBehavior = target(ctx, signal)
              try {
                eventSourcedSetup.onSignal(signal)
              } catch {
                case NonFatal(ex) =>
                  ctx.asScala.log.error(ex, s"Error while processing signal [{}]", signal)
              }
              nextBehavior
            }
            override def toString: String = "onStopInterceptor"
          }
          val widened = RequestingRecoveryPermit(eventSourcedSetup).widen[Any] {
            case res: JournalProtocol.Response           => InternalProtocol.JournalResponse(res)
            case res: SnapshotProtocol.Response          => InternalProtocol.SnapshotterResponse(res)
            case RecoveryPermitter.RecoveryPermitGranted => InternalProtocol.RecoveryPermitGranted
            case internal: InternalProtocol              => internal // such as RecoveryTickEvent
            case cmd: Command @unchecked                 => InternalProtocol.IncomingCommand(cmd)
          }
          Behaviors.intercept(onStopInterceptor)(widened).narrow[Command]
        }

      }
      .onFailure[JournalFailureException](supervisionStrategy)
  }

  override def receiveSignal(handler: PartialFunction[Signal, Unit]): EventSourcedBehavior[Command, Event, State] =
    copy(signalHandler = handler)

  override def snapshotWhen(predicate: (State, Event, Long) => Boolean): EventSourcedBehavior[Command, Event, State] =
    copy(snapshotWhen = predicate)

  override def snapshotEvery(numberOfEvents: Long): EventSourcedBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotWhen = (_, _, seqNr) => seqNr % numberOfEvents == 0)
  }

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
    copy(recovery = Recovery(selection))
  }

  override def withRetention(criteria: RetentionCriteria): EventSourcedBehavior[Command, Event, State] =
    copy(retention = criteria)

  override def withTagger(tagger: Event => Set[String]): EventSourcedBehavior[Command, Event, State] =
    copy(tagger = tagger)

  override def eventAdapter(adapter: EventAdapter[Event, _]): EventSourcedBehavior[Command, Event, State] =
    copy(eventAdapter = adapter.asInstanceOf[EventAdapter[Event, Any]])

  override def onPersistFailure(
      backoffStrategy: BackoffSupervisorStrategy): EventSourcedBehavior[Command, Event, State] =
    copy(supervisionStrategy = backoffStrategy)

}

/** Protocol used internally by the eventsourced behaviors. */
@InternalApi private[akka] sealed trait InternalProtocol
@InternalApi private[akka] object InternalProtocol {
  case object RecoveryPermitGranted extends InternalProtocol
  final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends InternalProtocol
  final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends InternalProtocol
  final case class RecoveryTickEvent(snapshot: Boolean) extends InternalProtocol
  final case class IncomingCommand[C](c: C) extends InternalProtocol
}
