/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.query.javadsl

import java.util.UUID

trait Offset {
  def hashCode: Int
  def asScala: akka.persistence.query.scaladsl.Offset
}

class Sequence(val value: Long) extends Offset {
  override def asScala: akka.persistence.query.scaladsl.Sequence = akka.persistence.query.scaladsl.Sequence(value)
}

class TimeBasedUUID(val value: UUID) extends Offset {
  override def asScala: akka.persistence.query.scaladsl.TimeBasedUUID = akka.persistence.query.scaladsl.TimeBasedUUID(value)
}

object NoOffset extends Offset {
  override def asScala = akka.persistence.query.scaladsl.NoOffset
}
