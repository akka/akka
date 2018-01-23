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

  @InternalApi private[akka] object WriterIdentity {
    def newIdentity(): WriterIdentity = {
      val instanceId: Int = EventsourcedBehavior.instanceIdCounter.getAndIncrement()
      val writerUuid: String = UUID.randomUUID.toString
      WriterIdentity(instanceId, writerUuid)
    }
  }
  private[akka] final case class WriterIdentity(instanceId: Int, writerUuid: String)

  /** Protocol used internally by the eventsourced behaviors, never exposed to user-land */
  private[akka] sealed trait EventsourcedProtocol
  private[akka] case object RecoveryPermitGranted extends EventsourcedProtocol
  private[akka] final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends EventsourcedProtocol
  private[akka] final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends EventsourcedProtocol
  private[akka] final case class RecoveryTickEvent(snapshot: Boolean) extends EventsourcedProtocol
  private[akka] final case class ReceiveTimeout(timeout: akka.actor.ReceiveTimeout) extends EventsourcedProtocol

  implicit object PersistentBehaviorLogSource extends LogSource[EventsourcedBehavior[_, _, _]] {
    override def genString(b: EventsourcedBehavior[_, _, _]): String = {
      val behaviorShortName = b match {
        case _: EventsourcedRunning[_, _, _]                  ⇒ "running"
        case _: EventsourcedRecoveringEvents[_, _, _]         ⇒ "recover-events"
        case _: EventsourcedRecoveringSnapshot[_, _, _]       ⇒ "recover-snap"
        case _: EventsourcedRequestingRecoveryPermit[_, _, _] ⇒ "awaiting-permit"
      }
      s"PersistentBehavior[id:${b.persistenceId}][${b.context.self.path}][$behaviorShortName]"
    }
  }

}

/** INTERNAL API */
@InternalApi
private[akka] trait EventsourcedBehavior[Command, Event, State] {
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected def context: ActorContext[Any]
  protected def timers: TimerScheduler[Any]

  type C = Command
  type AC = ActorContext[C]
  type E = Event
  type S = State

  // used for signaling intent in type signatures
  type SeqNr = Long

  def persistenceId: String

  protected def callbacks: EventsourcedCallbacks[Command, Event, State]
  protected def initialState: State = callbacks.initialState
  protected def commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State] = callbacks.commandHandler
  protected def eventHandler: (State, Event) ⇒ State = callbacks.eventHandler
  protected def snapshotWhen: (State, Event, SeqNr) ⇒ Boolean = callbacks.snapshotWhen
  protected def tagger: Event ⇒ Set[String] = callbacks.tagger

  protected def pluginIds: EventsourcedPluginIds
  protected final def journalPluginId: String = pluginIds.journalPluginId
  protected final def snapshotPluginId: String = pluginIds.snapshotPluginId

  // ------ common -------

  protected lazy val extension = Persistence(context.system.toUntyped)
  protected lazy val journal: a.ActorRef = extension.journalFor(journalPluginId)
  protected lazy val snapshotStore: a.ActorRef = extension.snapshotStoreFor(snapshotPluginId)

  protected lazy val selfUntyped: a.ActorRef = context.self.toUntyped
  protected lazy val selfUntypedAdapted: a.ActorRef = context.messageAdapter[Any] {
    case res: JournalProtocol.Response           ⇒ JournalResponse(res)
    case RecoveryPermitter.RecoveryPermitGranted ⇒ RecoveryPermitGranted
    case res: SnapshotProtocol.Response          ⇒ SnapshotterResponse(res)
    case cmd: Command @unchecked                 ⇒ cmd // if it was wrong, we'll realise when trying to onMessage the cmd
  }.toUntyped

}
