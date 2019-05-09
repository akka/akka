/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

import java.util.UUID

object Offset {

  // factories to aid discoverability
  def noOffset: Offset = NoOffset
  def sequence(value: Long): Offset = Sequence(value)
  def timeBasedUUID(uuid: UUID): Offset = TimeBasedUUID(uuid)

}

abstract class Offset

/**
 * Corresponds to an ordered sequence number for the events. Note that the corresponding
 * offset of each event is provided in the [[akka.persistence.query.EventEnvelope]],
 * which makes it possible to resume the stream at a later point from a given offset.
 *
 * The `offset` is exclusive, i.e. the event with the exact same sequence number will not be included
 * in the returned stream. This means that you can use the offset that is returned in `EventEnvelope`
 * as the `offset` parameter in a subsequent query.
 */
final case class Sequence(value: Long) extends Offset with Ordered[Sequence] {
  override def compare(that: Sequence): Int = value.compare(that.value)
}

/**
 * Corresponds to an ordered unique identifier of the events. Note that the corresponding
 * offset of each event is provided in the [[akka.persistence.query.EventEnvelope]],
 * which makes it possible to resume the stream at a later point from a given offset.
 *
 * The `offset` is exclusive, i.e. the event with the exact same sequence number will not be included
 * in the returned stream. This means that you can use the offset that is returned in `EventEnvelope`
 * as the `offset` parameter in a subsequent query.
 */
final case class TimeBasedUUID(value: UUID) extends Offset with Ordered[TimeBasedUUID] {
  if (value == null || value.version != 1) {
    throw new IllegalArgumentException("UUID " + value + " is not a time-based UUID")
  }

  override def compare(other: TimeBasedUUID): Int = value.compareTo(other.value)
}

/**
 * Used when retrieving all events.
 */
final case object NoOffset extends Offset {

  /**
   * Java API:
   */
  def getInstance: Offset = this
}
