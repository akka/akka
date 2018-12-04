/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

package object internal {

  /** Protocol used internally by the eventsourced behaviors. */
  sealed trait InternalProtocol
  object InternalProtocol {
    case object RecoveryPermitGranted extends InternalProtocol
    final case class JournalResponse(msg: akka.persistence.JournalProtocol.Response) extends InternalProtocol
    final case class SnapshotterResponse(msg: akka.persistence.SnapshotProtocol.Response) extends InternalProtocol
    final case class RecoveryTickEvent(snapshot: Boolean) extends InternalProtocol
    final case class IncomingCommand[C](c: C) extends InternalProtocol
  }
}
