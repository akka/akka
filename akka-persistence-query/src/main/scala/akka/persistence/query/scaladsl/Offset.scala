/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.query.scaladsl

import java.util.UUID
import akka.persistence.query.javadsl

trait Offset extends javadsl.Offset {
  def hashCode: Int
}

final case class Sequence(override val value: Long) extends javadsl.Sequence(value) with Offset with Ordered[Sequence] {

  override def hashCode: Int = value.hashCode

  override def toString: String = value.toString

  override def compare(that: Sequence): Int = value.compare(that.value)
}

final case class TimeBasedUUID(override val value: UUID) extends javadsl.TimeBasedUUID(value) with Offset with Ordered[TimeBasedUUID] {
  if (value == null || value.version != 1) {
    throw new IllegalArgumentException("UUID " + value + " is not a time-based UUID")
  }

  override def hashCode: Int = value.hashCode

  override def toString: String = value.toString

  override def compare(other: TimeBasedUUID): Int = value.compareTo(other.value)
}

final case object NoOffset extends Offset {
  override def hashCode: Int = 0

  override def toString: String = "NoOffset"

  override def asScala = NoOffset
}
