/*
 * Copyright (C) 2021-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed

import java.util.{Set => JSet}
import java.util.Optional

import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi
import akka.persistence.query.Offset
import akka.util.HashCode
import akka.util.ccompat.JavaConverters._

object EventEnvelope {

  def apply[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String,
      tags: Set[String]): EventEnvelope[Event] =
    new EventEnvelope(
      offset,
      persistenceId,
      sequenceNr,
      Option(event),
      timestamp,
      None,
      entityType,
      slice,
      filtered,
      source,
      tags)

  def apply[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String): EventEnvelope[Event] =
    new EventEnvelope(
      offset,
      persistenceId,
      sequenceNr,
      Option(event),
      timestamp,
      None,
      entityType,
      slice,
      filtered,
      source)

  def apply[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int): EventEnvelope[Event] =
    apply(offset, persistenceId, sequenceNr, event, timestamp, entityType, slice, filtered = false, source = "")

  def apply[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      serializedEvent: SerializedEvent,
      timestamp: Long,
      eventMetadata: Option[Any],
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String,
      tags: Set[String]): EventEnvelope[Event] =
    new EventEnvelope[Event](
      offset,
      persistenceId,
      sequenceNr,
      None,
      Some(serializedEvent),
      timestamp,
      eventMetadata,
      entityType,
      slice,
      filtered,
      source,
      tags)

  def create[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String,
      tags: JSet[String]): EventEnvelope[Event] =
    apply(offset, persistenceId, sequenceNr, event, timestamp, entityType, slice, filtered, source, tags.asScala.toSet)

  def create[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String): EventEnvelope[Event] =
    apply(offset, persistenceId, sequenceNr, event, timestamp, entityType, slice, filtered, source)

  def create[Event](
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      event: Event,
      timestamp: Long,
      entityType: String,
      slice: Int): EventEnvelope[Event] =
    create(offset, persistenceId, sequenceNr, event, timestamp, entityType, slice, filtered = false, source = "")

  def unapply[Event](arg: EventEnvelope[Event]): Option[(Offset, String, Long, Option[Event], Long)] =
    Some((arg.offset, arg.persistenceId, arg.sequenceNr, arg.eventOption, arg.timestamp))

  /**
   * INTERNAL API
   */
  @InternalStableApi private[akka] final case class SerializedEvent(
      bytes: Array[Byte],
      serializerId: Int,
      serializerManifest: String)
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
final class EventEnvelope[Event] private (
    val offset: Offset,
    val persistenceId: String,
    val sequenceNr: Long,
    private val _eventOption: Option[Event],
    /** INTERNAL API */
    @InternalStableApi private[akka] val serializedEvent: Option[EventEnvelope.SerializedEvent],
    val timestamp: Long,
    val eventMetadata: Option[Any],
    val entityType: String,
    val slice: Int,
    val filtered: Boolean,
    val source: String,
    val tags: Set[String]) {

  def this(
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      eventOption: Option[Event],
      timestamp: Long,
      eventMetadata: Option[Any],
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String,
      tags: Set[String]) =
    this(
      offset,
      persistenceId,
      sequenceNr,
      eventOption,
      None,
      timestamp,
      eventMetadata,
      entityType,
      slice,
      filtered,
      source,
      tags)

  def this(
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      eventOption: Option[Event],
      timestamp: Long,
      eventMetadata: Option[Any],
      entityType: String,
      slice: Int,
      filtered: Boolean,
      source: String) =
    this(
      offset,
      persistenceId,
      sequenceNr,
      eventOption,
      None,
      timestamp,
      eventMetadata,
      entityType,
      slice,
      filtered,
      source,
      tags = Set.empty[String])

  def this(
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long,
      eventOption: Option[Event],
      timestamp: Long,
      eventMetadata: Option[Any],
      entityType: String,
      slice: Int) =
    this(
      offset,
      persistenceId,
      sequenceNr,
      eventOption,
      timestamp,
      eventMetadata,
      entityType,
      slice,
      filtered = false,
      source = "")

  def eventOption: Option[Event] = {
    if (serializedEvent.isDefined && _eventOption.isEmpty)
      throw new IllegalStateException(
        "Event has not been deserialized yet. This is a bug, please report at https://github.com/akka/akka/issues")
    _eventOption
  }

  def event: Event =
    eventOption match {
      case Some(evt) => evt
      case None =>
        if (filtered) {
          throw new IllegalStateException(
            "Event was filtered so payload is not present. Use eventOption to handle more gracefully.")
        } else {
          throw new IllegalStateException(
            "Event was not loaded. Use eventOption and load the event on demand with LoadEventQuery.")
        }
    }

  /**
   * Java API
   */
  def getEvent(): Event =
    eventOption match {
      case Some(evt) => evt
      case None =>
        if (filtered) {
          throw new IllegalStateException(
            "Event was filtered so payload is not present. Use getOptionalEvent to handle more gracefully.")
        } else {
          throw new IllegalStateException(
            "Event was not loaded. Use getOptionalEvent and load the event on demand with LoadEventQuery.")
        }
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

  /**
   * Java API:
   */
  def getTags(): JSet[String] = tags.asJava

  def withPersistenceId(persistenceId: String): EventEnvelope[Event] =
    copy(persistenceId = persistenceId)

  def withEvent(event: Event): EventEnvelope[Event] =
    copy(_eventOption = Option(event))

  def withEventOption(eventOption: Option[Event]): EventEnvelope[Event] =
    copy(_eventOption = eventOption)

  def withTags(tags: Set[String]): EventEnvelope[Event] =
    copy(tags = tags)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka]  def isEventDeserialized: Boolean =
    _eventOption.isDefined || serializedEvent.isEmpty

  private def copy(
      offset: Offset = offset,
      persistenceId: String = persistenceId,
      sequenceNr: Long = sequenceNr,
      _eventOption: Option[Event] = _eventOption,
      serializedEvent: Option[EventEnvelope.SerializedEvent] = serializedEvent,
      timestamp: Long = timestamp,
      eventMetadata: Option[Any] = eventMetadata,
      entityType: String = entityType,
      slice: Int = slice,
      filtered: Boolean = filtered,
      source: String = source,
      tags: Set[String] = tags): EventEnvelope[Event] = {
    new EventEnvelope(
      offset,
      persistenceId,
      sequenceNr,
      _eventOption,
      serializedEvent,
      timestamp,
      eventMetadata,
      entityType,
      slice,
      filtered,
      source,
      tags)
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
      _eventOption == other._eventOption && // FIXME compare combinations of eventOption and serializedEvent
      timestamp == other.timestamp && eventMetadata == other.eventMetadata &&
      entityType == other.entityType && slice == other.slice && filtered == other.filtered &&
      tags == other.tags
    case _ => false
  }

  override def toString: String = {
    val eventStr =
      _eventOption match {
        case Some(evt) => evt.getClass.getName
        case None =>
          serializedEvent match {
            case Some(EventEnvelope.SerializedEvent(_, serializerId, serializerManifest)) =>
              s"SerializedEvent($serializerId, $serializerManifest)"
            case None => ""
          }
      }
    val metaStr = eventMetadata match {
      case Some(meta) => meta.getClass.getName
      case None       => ""
    }
    s"EventEnvelope($offset,$persistenceId,$sequenceNr,$eventStr,$timestamp,$metaStr,$entityType,$slice,$filtered,$source,${tags
      .mkString("[", ", ", "]")})"
  }
}
