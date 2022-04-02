/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed

import java.util.Optional

import akka.annotation.ApiMayChange
import akka.persistence.query.Offset
import akka.util.HashCode

object EventEnvelope {
  def apply[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int): EventEnvelope[Event] =
    new EventEnvelope(offset, persistenceId, sequenceNr, Option(event), timestamp, None, entityType, slice)

  def create[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int): EventEnvelope[Event] =
    apply(offset, persistenceId, sequenceNr, event, timestamp, entityType, slice)

  def unapply[Event](arg: EventEnvelope[Event]): Option[(Offset, String, Long, Option[Event], Long)] =
    Some((arg.offset, arg.persistenceId, arg.sequenceNr, arg.eventOption, arg.timestamp))
}

/**
 * Event wrapper adding meta data for the events in the result stream of
 * [[akka.persistence.query.typed.scaladsl.EventsBySliceQuery]] query, or similar queries.
 *
 * If the `event` is not defined it has not been loaded yet. It can be loaded with
 * [[akka.persistence.query.typed.scaladsl.LoadEventQuery]].
 *
 * The `timestamp` is the time the event was stored, in milliseconds since midnight, January 1, 1970 UTC (same as
 * `System.currentTimeMillis`).
 *
 * It is an improved `EventEnvelope` compared to [[akka.persistence.query.EventEnvelope]].
 *
 * API May Change
 */
@ApiMayChange
final class EventEnvelope[Event](
    val offset: Offset,
    val persistenceId: String,
    val sequenceNr: Long,
    val eventOption: Option[Event],
    val timestamp: Long,
    val eventMetadata: Option[Any],
    val entityType: String,
    val slice: Int) {

  def event: Event =
    eventOption match {
      case Some(evt) => evt
      case None =>
        throw new IllegalStateException(
          "Event was not loaded. Use eventOption and load the event on demand with LoadEventQuery.")
    }

  /**
   * Java API
   */
  def getEvent(): Event =
    eventOption match {
      case Some(evt) => evt
      case None =>
        throw new IllegalStateException(
          "Event was not loaded. Use getOptionalEvent and load the event on demand with LoadEventQuery.")
    }

  /**
   * Java API
   */
  def getOptionalEvent(): Optional[Event] = {
    import scala.compat.java8.OptionConverters._
    eventOption.asJava
  }

  /**
   * Java API
   */
  def getEventMetaData(): Optional[AnyRef] = {
    import scala.compat.java8.OptionConverters._
    eventMetadata.map(_.asInstanceOf[AnyRef]).asJava
  }

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, offset)
    result = HashCode.hash(result, persistenceId)
    result = HashCode.hash(result, sequenceNr)
    result
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: EventEnvelope[_] =>
      offset == other.offset && persistenceId == other.persistenceId && sequenceNr == other.sequenceNr &&
      eventOption == other.eventOption && timestamp == other.timestamp && eventMetadata == other.eventMetadata &&
      entityType == other.entityType && slice == other.slice
    case _ => false
  }

  override def toString: String =
    s"EventEnvelope($offset,$persistenceId,$sequenceNr,$eventOption,$timestamp,$eventMetadata,$entityType,$slice)"
}
