/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, ValueRenderable }

sealed trait ContentRange extends ValueRenderable

sealed trait ByteContentRange extends ContentRange {
  def instanceLength: Option[Long]
}

// http://tools.ietf.org/html/rfc7233#section-4.2
object ContentRange {
  def apply(first: Long, last: Long): Default = apply(first, last, None)
  def apply(first: Long, last: Long, instanceLength: Long): Default = apply(first, last, Some(instanceLength))
  def apply(first: Long, last: Long, instanceLength: Option[Long]): Default = Default(first, last, instanceLength)

  /**
   * Models a satisfiable HTTP content-range.
   */
  final case class Default(first: Long, last: Long, instanceLength: Option[Long]) extends ByteContentRange {
    require(0 <= first && first <= last, "first must be >= 0 and <= last")
    require(instanceLength.isEmpty || instanceLength.get > last, "instanceLength must be empty or > last")

    def render[R <: Rendering](r: R): r.type = {
      r ~~ first ~~ '-' ~~ last ~~ '/'
      if (instanceLength.isDefined) r ~~ instanceLength.get else r ~~ '*'
    }
  }

  /**
   * An unsatisfiable content-range.
   */
  final case class Unsatisfiable(length: Long) extends ByteContentRange {
    val instanceLength = Some(length)
    def render[R <: Rendering](r: R): r.type = r ~~ "*/" ~~ length
  }

  /**
   * An `other-range-resp`.
   */
  final case class Other(override val value: String) extends ContentRange {
    def render[R <: Rendering](r: R): r.type = r ~~ value
  }
}