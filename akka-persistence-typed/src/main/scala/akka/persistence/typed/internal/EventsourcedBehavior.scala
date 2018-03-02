/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.NoSerializationVerificationNeeded
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.StoppedBehavior
import akka.actor.typed.scaladsl.{ ActorContext, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.{ LogSource, Logging }
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.{ JournalProtocol, Persistence, RecoveryPermitter, SnapshotProtocol }
import akka.{ actor ⇒ a }

/** INTERNAL API */
@InternalApi
private[akka] object EventsourcedBehavior {

  // ok to wrap around (2*Int.MaxValue restarts will not happen within a journal roundtrip)
  private[akka] val instanceIdCounter = new AtomicInteger(1)

  object WriterIdentity {
    def newIdentity(): WriterIdentity = {
      val instanceId: Int = EventsourcedBehavior.instanceIdCounter.getAndIncrement()
      val writerUuid: String = UUID.randomUUID.toString
      WriterIdentity(instanceId, writerUuid)
    }
  }
  final case class WriterIdentity(instanceId: Int, writerUuid: String)

  /** Protocol used internally by the eventsourced behaviors, never exposed to user-land */
  sealed trait InternalProtocol
  object InternalProtocol {
    case object RecoveryPermitGranted extends InternalProtocol
    final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends InternalProtocol
    final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends InternalProtocol
    final case class RecoveryTickEvent(snapshot: Boolean) extends InternalProtocol
    final case class ReceiveTimeout(timeout: akka.actor.ReceiveTimeout) extends InternalProtocol
    final case class IncomingCommand[C](c: C) extends InternalProtocol
  }
  //  implicit object PersistentBehaviorLogSource extends LogSource[EventsourcedBehavior[_, _, _]] {
  //    override def genString(b: EventsourcedBehavior[_, _, _]): String = {
  //      val behaviorShortName = b match {
  //        case _: EventsourcedRunning[_, _, _]                  ⇒ "running"
  //        case _: EventsourcedRecoveringEvents[_, _, _]         ⇒ "recover-events"
  //        case _: EventsourcedRecoveringSnapshot[_, _, _]       ⇒ "recover-snap"
  //        case _: EventsourcedRequestingRecoveryPermit[_, _, _] ⇒ "awaiting-permit"
  //      }
  //      s"PersistentBehavior[id:${b.persistenceId}][${b.context.self.path}][$behaviorShortName]"
  //    }
  //  }

}

/** INTERNAL API */
@InternalApi
private[akka] trait EventsourcedBehavior[Command, Event, State] {
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  //  protected def timers: TimerScheduler[Any]

  type C = Command
  type AC = ActorContext[C]
  type E = Event
  type S = State

  // used for signaling intent in type signatures
  type SeqNr = Long

  //  def persistenceId: String = setup.persistenceId
  //
  //  protected def setup: EventsourcedSetup[Command, Event, State]
  //  protected def initialState: State = setup.initialState
  //  protected def commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State] = setup.commandHandler
  //  protected def eventHandler: (State, Event) ⇒ State = setup.eventHandler
  //  protected def snapshotWhen: (State, Event, SeqNr) ⇒ Boolean = setup.snapshotWhen
  //  protected def tagger: Event ⇒ Set[String] = setup.tagger
  //
  //  protected final def journalPluginId: String = setup.journalPluginId
  //  protected final def snapshotPluginId: String = setup.snapshotPluginId

  // ------ common -------

  //  protected lazy val extension = Persistence(context.system.toUntyped)
  //  protected lazy val journal: a.ActorRef = extension.journalFor(journalPluginId)
  //  protected lazy val snapshotStore: a.ActorRef = extension.snapshotStoreFor(snapshotPluginId)
  //
  //  protected lazy val selfUntyped: a.ActorRef = context.self.toUntyped
  //  protected lazy val selfUntypedAdapted: a.ActorRef = context.messageAdapter[Any] {
  //    case res: JournalProtocol.Response           ⇒ JournalResponse(res)
  //    case RecoveryPermitter.RecoveryPermitGranted ⇒ RecoveryPermitGranted
  //    case res: SnapshotProtocol.Response          ⇒ SnapshotterResponse(res)
  //    case cmd: Command @unchecked                 ⇒ cmd // if it was wrong, we'll realise when trying to onMessage the cmd
  //  }.toUntyped

}
