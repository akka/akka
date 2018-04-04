/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi

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

  object MDC {
    // format: OFF
    val AwaitingPermit    = "get-permit"
    val ReplayingSnapshot = "replay-snap"
    val ReplayingEvents   = "replay-evts"
    val RunningCmds       = "running-cmnds"
    val PersistingEvents  = "persist-evts"
    // format: ON

    def create(persistenceId: String, phaseName: String): Map[String, Any] = {
      Map(
        "persistenceId" → persistenceId,
        "phase" → phaseName
      )
    }
  }

  /** Protocol used internally by the eventsourced behaviors, never exposed to user-land */
  sealed trait InternalProtocol
  object InternalProtocol {
    case object RecoveryPermitGranted extends InternalProtocol
    final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends InternalProtocol
    final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends InternalProtocol
    final case class RecoveryTickEvent(snapshot: Boolean) extends InternalProtocol
    final case class IncomingCommand[C](c: C) extends InternalProtocol
  }
}
