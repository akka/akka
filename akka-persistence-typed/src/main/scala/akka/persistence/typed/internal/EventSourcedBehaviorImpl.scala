/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.Done
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
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.NoOpEventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl._
import akka.util.ConstantFun

@InternalApi
private[akka] object EventSourcedBehaviorImpl {

  def defaultOnSnapshot[A](ctx: ActorContext[A], meta: SnapshotMetadata, result: Try[Done]): Unit = {
    result match {
      case Success(_) ⇒
        ctx.log.debug("Save snapshot successful, snapshot metadata: [{}]", meta)
      case Failure(t) ⇒
        ctx.log.error(t, "Save snapshot failed, snapshot metadata: [{}]", meta)
    }
  }

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
  persistenceId:       PersistenceId,
  emptyState:          State,
  commandHandler:      EventSourcedBehavior.CommandHandler[Command, Event, State],
  eventHandler:        EventSourcedBehavior.EventHandler[State, Event],
  journalPluginId:     Option[String]                                             = None,
  snapshotPluginId:    Option[String]                                             = None,
  recoveryCompleted:   State ⇒ Unit                                               = ConstantFun.scalaAnyToUnit,
  tagger:              Event ⇒ Set[String]                                        = (_: Event) ⇒ Set.empty[String],
  eventAdapter:        EventAdapter[Event, Any]                                   = NoOpEventAdapter.instance[Event],
  snapshotWhen:        (State, Event, Long) ⇒ Boolean                             = ConstantFun.scalaAnyThreeToFalse,
  recovery:            Recovery                                                   = Recovery(),
  supervisionStrategy: SupervisorStrategy                                         = SupervisorStrategy.stop,
  onSnapshot:          (SnapshotMetadata, Try[Done]) ⇒ Unit                       = ConstantFun.scalaAnyTwoToUnit,
  onRecoveryFailure:   Throwable ⇒ Unit                                           = ConstantFun.scalaAnyToUnit
) extends EventSourcedBehavior[Command, Event, State] {

  import EventSourcedBehaviorImpl.WriterIdentity

  override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] = {
    val ctx = context.asScala
    val settings = EventSourcedSettings(ctx.system, journalPluginId.getOrElse(""), snapshotPluginId.getOrElse(""))

    // stashState outside supervise because StashState should survive restarts due to persist failures
    val stashState = new StashState(settings)

    Behaviors.supervise {
      Behaviors.setup[Command] { _ ⇒

        // the default impl needs context which isn't available until here, so we
        // use the anyTwoToUnit as a marker to use the default
        val actualOnSnapshot: (SnapshotMetadata, Try[Done]) ⇒ Unit =
          if (onSnapshot == ConstantFun.scalaAnyTwoToUnit) EventSourcedBehaviorImpl.defaultOnSnapshot[Command](ctx, _, _)
          else onSnapshot

        val eventsourcedSetup = new BehaviorSetup(
          ctx.asInstanceOf[ActorContext[InternalProtocol]],
          persistenceId,
          emptyState,
          commandHandler,
          eventHandler,
          WriterIdentity.newIdentity(),
          recoveryCompleted,
          onRecoveryFailure,
          actualOnSnapshot,
          tagger,
          eventAdapter,
          snapshotWhen,
          recovery,
          holdingRecoveryPermit = false,
          settings = settings,
          stashState = stashState
        )

        // needs to accept Any since we also can get messages from the journal
        // not part of the protocol
        val onStopInterceptor = new BehaviorInterceptor[Any, Any] {

          import BehaviorInterceptor._
          def aroundReceive(ctx: typed.TypedActorContext[Any], msg: Any, target: ReceiveTarget[Any]): Behavior[Any] = {
            target(ctx, msg)
          }

          def aroundSignal(ctx: typed.TypedActorContext[Any], signal: Signal, target: SignalTarget[Any]): Behavior[Any] = {
            if (signal == PostStop) {
              eventsourcedSetup.cancelRecoveryTimer()
              // clear stash to be GC friendly
              stashState.clearStashBuffers()
            }
            target(ctx, signal)
          }
        }
        val widened = RequestingRecoveryPermit(eventsourcedSetup).widen[Any] {
          case res: JournalProtocol.Response           ⇒ InternalProtocol.JournalResponse(res)
          case res: SnapshotProtocol.Response          ⇒ InternalProtocol.SnapshotterResponse(res)
          case RecoveryPermitter.RecoveryPermitGranted ⇒ InternalProtocol.RecoveryPermitGranted
          case internal: InternalProtocol              ⇒ internal // such as RecoveryTickEvent
          case cmd: Command @unchecked                 ⇒ InternalProtocol.IncomingCommand(cmd)
        }
        Behaviors.intercept(onStopInterceptor)(widened).narrow[Command]
      }

    }.onFailure[JournalFailureException](supervisionStrategy)
  }

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: State ⇒ Unit): EventSourcedBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): EventSourcedBehavior[Command, Event, State] =
    copy(snapshotWhen = predicate)

  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): EventSourcedBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotWhen = (_, _, seqNr) ⇒ seqNr % numberOfEvents == 0)
  }

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): EventSourcedBehavior[Command, Event, State] = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = if (id != "") Some(id) else None)
  }

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): EventSourcedBehavior[Command, Event, State] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(snapshotPluginId = if (id != "") Some(id) else None)
  }

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): EventSourcedBehavior[Command, Event, State] = {
    copy(recovery = Recovery(selection))
  }

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): EventSourcedBehavior[Command, Event, State] =
    copy(tagger = tagger)

  /**
   * Adapt the event before sending to the journal e.g. wrapping the event in a type
   * the journal understands
   */
  def eventAdapter(adapter: EventAdapter[Event, _]): EventSourcedBehavior[Command, Event, State] =
    copy(eventAdapter = adapter.asInstanceOf[EventAdapter[Event, Any]])

  /**
   * The `callback` function is called to notify the actor that a snapshot has finished
   */
  def onSnapshot(callback: (SnapshotMetadata, Try[Done]) ⇒ Unit): EventSourcedBehavior[Command, Event, State] =
    copy(onSnapshot = callback)

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): EventSourcedBehavior[Command, Event, State] =
    copy(supervisionStrategy = backoffStrategy)

  /**
   * The `callback` function is called to notify that recovery has failed. For setting a supervision
   * strategy `onPersistFailure`
   */
  def onRecoveryFailure(callback: Throwable ⇒ Unit): EventSourcedBehavior[Command, Event, State] =
    copy(onRecoveryFailure = callback)
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
