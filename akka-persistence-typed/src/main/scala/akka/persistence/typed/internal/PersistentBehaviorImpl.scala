/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.Done
import akka.actor.typed
import akka.actor.typed.{ BackoffSupervisorStrategy, Behavior, BehaviorInterceptor, PostStop, Signal, SupervisorStrategy }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.{ EventAdapter, NoOpEventAdapter }
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }
import akka.persistence.typed.scaladsl._
import akka.util.ConstantFun
import scala.util.{ Failure, Success, Try }

import akka.persistence.typed.PersistenceId

@InternalApi
private[akka] object PersistentBehaviorImpl {

  def defaultOnSnapshot[A](ctx: ActorContext[A], meta: SnapshotMetadata, result: Try[Done]): Unit = {
    result match {
      case Success(_) ⇒
        ctx.log.debug("Save snapshot successful, snapshot metadata: [{}]", meta)
      case Failure(t) ⇒
        ctx.log.error(t, "Save snapshot failed, snapshot metadata: [{}]", meta)
    }
  }
}

@InternalApi
private[akka] final case class PersistentBehaviorImpl[Command, Event, State](
  persistenceId:       PersistenceId,
  emptyState:          State,
  commandHandler:      PersistentBehavior.CommandHandler[Command, Event, State],
  eventHandler:        PersistentBehavior.EventHandler[State, Event],
  journalPluginId:     Option[String]                                           = None,
  snapshotPluginId:    Option[String]                                           = None,
  recoveryCompleted:   State ⇒ Unit                                             = ConstantFun.scalaAnyToUnit,
  tagger:              Event ⇒ Set[String]                                      = (_: Event) ⇒ Set.empty[String],
  eventAdapter:        EventAdapter[Event, Any]                                 = NoOpEventAdapter.instance[Event],
  snapshotWhen:        (State, Event, Long) ⇒ Boolean                           = ConstantFun.scalaAnyThreeToFalse,
  recovery:            Recovery                                                 = Recovery(),
  supervisionStrategy: SupervisorStrategy                                       = SupervisorStrategy.stop,
  onSnapshot:          (SnapshotMetadata, Try[Done]) ⇒ Unit                     = ConstantFun.scalaAnyTwoToUnit
) extends PersistentBehavior[Command, Event, State] with EventsourcedStashReferenceManagement {

  override def apply(context: typed.ActorContext[Command]): Behavior[Command] = {
    Behaviors.supervise {
      Behaviors.setup[Command] { ctx ⇒
        val settings = EventsourcedSettings(ctx.system, journalPluginId.getOrElse(""), snapshotPluginId.getOrElse(""))

        val internalStash = stashBuffer(settings)

        // the default impl needs context which isn't available until here, so we
        // use the anyTwoToUnit as a marker to use the default
        val actualOnSnapshot: (SnapshotMetadata, Try[Done]) ⇒ Unit =
          if (onSnapshot == ConstantFun.scalaAnyTwoToUnit) PersistentBehaviorImpl.defaultOnSnapshot[Command](ctx, _, _)
          else onSnapshot

        val eventsourcedSetup = new EventsourcedSetup(
          ctx.asInstanceOf[ActorContext[InternalProtocol]],
          persistenceId,
          emptyState,
          commandHandler,
          eventHandler,
          WriterIdentity.newIdentity(),
          recoveryCompleted,
          actualOnSnapshot,
          tagger,
          eventAdapter,
          snapshotWhen,
          recovery,
          holdingRecoveryPermit = false,
          settings = settings,
          internalStash = internalStash
        )

        // needs to accept Any since we also can get messages from the journal
        // not part of the protocol
        val onStopInterceptor = new BehaviorInterceptor[Any, Any] {
          import BehaviorInterceptor._
          def aroundReceive(ctx: typed.ActorContext[Any], msg: Any, target: ReceiveTarget[Any]): Behavior[Any] = {
            target(ctx, msg)
          }

          def aroundSignal(ctx: typed.ActorContext[Any], signal: Signal, target: SignalTarget[Any]): Behavior[Any] = {
            if (signal == PostStop) {
              eventsourcedSetup.cancelRecoveryTimer()
              clearStashBuffer()
            }
            target(ctx, signal)
          }
        }
        val widened = EventsourcedRequestingRecoveryPermit(eventsourcedSetup).widen[Any] {
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
  def onRecoveryCompleted(callback: State ⇒ Unit): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior[Command, Event, State] =
    copy(snapshotWhen = predicate)

  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotWhen = (_, _, seqNr) ⇒ seqNr % numberOfEvents == 0)
  }

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = if (id != "") Some(id) else None)
  }

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior[Command, Event, State] = {
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
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior[Command, Event, State] = {
    copy(recovery = Recovery(selection))
  }

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State] =
    copy(tagger = tagger)

  /**
   * Adapt the event before sending to the journal e.g. wrapping the event in a type
   * the journal understands
   */
  def eventAdapter(adapter: EventAdapter[Event, _]): PersistentBehavior[Command, Event, State] =
    copy(eventAdapter = adapter.asInstanceOf[EventAdapter[Event, Any]])

  /**
   * The `callback` function is called to notify the actor that a snapshot has finished
   */
  def onSnapshot(callback: (SnapshotMetadata, Try[Done]) ⇒ Unit): PersistentBehavior[Command, Event, State] =
    copy(onSnapshot = callback)

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): PersistentBehavior[Command, Event, State] =
    copy(supervisionStrategy = backoffStrategy)
}
