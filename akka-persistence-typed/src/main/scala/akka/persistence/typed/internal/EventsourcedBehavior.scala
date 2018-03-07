/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence.Persistence
import akka.persistence.typed.scaladsl.PersistentBehaviors
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
  sealed trait EventsourcedProtocol
  object EventsourcedProtocol {
    private[akka] case object RecoveryPermitGranted extends EventsourcedProtocol
    private[akka] final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends EventsourcedProtocol
    private[akka] final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends EventsourcedProtocol
    private[akka] final case class RecoveryTickEvent(snapshot: Boolean) extends EventsourcedProtocol
    private[akka] final case class ReceiveTimeout(timeout: akka.actor.ReceiveTimeout) extends EventsourcedProtocol
    private[akka] final case class IncomingCommand[C](command: C) extends EventsourcedProtocol
  }

  object PhaseName {
    // format: OFF
    val AwaitPermit     = "await-permit"
    val RecoverSnapshot = "recover-snap"
    val RecoverEvents   = "recover-evts"
    @Deprecated // FIXME handling commands / events has to be behaviors, otherwise hard to influence mdc
    val Running         = "running     "
    val HandleCmnds     = "handle-cmnds"
    val PersistEvts     = "persist-evts"
    // format: ON
  }

  def withMDC(persistenceId: String, phase: String)(b: Behavior[EventsourcedProtocol]) = {
    val mdc = Map("id" → persistenceId, "phase" → phase)
    Behaviors.withMdc((_: EventsourcedProtocol) ⇒ mdc, b)
  }

}

/** INTERNAL API */
@InternalApi
private[akka] trait EventsourcedBehavior[C, E, S] {
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected def context: ActorContext[EventsourcedProtocol]
  protected def timers: TimerScheduler[EventsourcedProtocol]

  def persistenceId: String = setup.persistenceId

  protected def setup: EventsourcedSetup[C, E, S]
  protected def initialState: S = setup.initialState
  protected def commandHandler: PersistentBehaviors.CommandHandler[C, E, S] = setup.commandHandler
  protected def eventHandler: (S, E) ⇒ S = setup.eventHandler
  protected def snapshotWhen: (S, E, Long) ⇒ Boolean = setup.snapshotWhen
  protected def tagger: E ⇒ Set[String] = setup.tagger

  protected final def journalPluginId: String = setup.journalPluginId
  protected final def snapshotPluginId: String = setup.snapshotPluginId

  // ------ common -------

  protected lazy val extension = Persistence(context.system.toUntyped)
  protected lazy val journal: a.ActorRef = extension.journalFor(journalPluginId)
  protected lazy val snapshotStore: a.ActorRef = extension.snapshotStoreFor(snapshotPluginId)

  protected lazy val selfUntyped: a.ActorRef = context.self.toUntyped
}
