/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.query

import java.util.UUID

trait Offset

final case class Sequence(val value: Long) extends Offset with Ordered[Sequence] {
  override def compare(that: Sequence): Int = value.compare(that.value)
}

final case class TimeBasedUUID(val value: UUID) extends Offset with Ordered[TimeBasedUUID] {
  if (value == null || value.version != 1) {
    throw new IllegalArgumentException("UUID " + value + " is not a time-based UUID")
  }

  override def compare(other: TimeBasedUUID): Int = value.compareTo(other.value)
}

final case object NoOffset extends Offset