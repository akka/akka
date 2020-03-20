/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

import scala.runtime.AbstractFunction4

import akka.util.HashCode

// for binary compatibility (used to be a case class)
object EventEnvelope extends AbstractFunction4[Offset, String, Long, Any, EventEnvelope] {
  def apply(offset: Offset, persistenceId: String, sequenceNr: Long, event: Any, timestamp: Long): EventEnvelope =
    new EventEnvelope(offset, persistenceId, sequenceNr, event, timestamp)

  @deprecated("for binary compatibility", "2.6.2")
  override def apply(offset: Offset, persistenceId: String, sequenceNr: Long, event: Any): EventEnvelope =
    new EventEnvelope(offset, persistenceId, sequenceNr, event)

  def unapply(arg: EventEnvelope): Option[(Offset, String, Long, Any)] =
    Some((arg.offset, arg.persistenceId, arg.sequenceNr, arg.event))

}

/**
 * Event wrapper adding meta data for the events in the result stream of
 * [[akka.persistence.query.scaladsl.EventsByTagQuery]] query, or similar queries.
 *
 * The `timestamp` is the time the event was stored, in milliseconds since midnight, January 1, 1970 UTC
 * (same as `System.currentTimeMillis`).
 */
final class EventEnvelope(
    val offset: Offset,
    val persistenceId: String,
    val sequenceNr: Long,
    val event: Any,
    val timestamp: Long)
    extends Product4[Offset, String, Long, Any]
    with Serializable {

  @deprecated("for binary compatibility", "2.6.2")
  def this(offset: Offset, persistenceId: String, sequenceNr: Long, event: Any) =
    this(offset, persistenceId, sequenceNr, event, 0L)

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, offset)
    result = HashCode.hash(result, persistenceId)
    result = HashCode.hash(result, sequenceNr)
    result = HashCode.hash(result, event)
    result
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: EventEnvelope =>
      offset == other.offset && persistenceId == other.persistenceId && sequenceNr == other.sequenceNr &&
      event == other.event // timestamp not included in equals for backwards compatibility
    case _ => false
  }

  override def toString: String =
    s"EventEnvelope($offset,$persistenceId,$sequenceNr,$event,$timestamp)"

  // for binary compatibility (used to be a case class)
  def copy(
      offset: Offset = this.offset,
      persistenceId: String = this.persistenceId,
      sequenceNr: Long = this.sequenceNr,
      event: Any = this.event): EventEnvelope =
    new EventEnvelope(offset, persistenceId, sequenceNr, event, timestamp)

  // Product4, for binary compatibility (used to be a case class)
  override def productPrefix = "EventEnvelope"
  override def _1: Offset = offset
  override def _2: String = persistenceId
  override def _3: Long = sequenceNr
  override def _4: Any = event
  override def canEqual(that: Any): Boolean = that.isInstanceOf[EventEnvelope]

}
