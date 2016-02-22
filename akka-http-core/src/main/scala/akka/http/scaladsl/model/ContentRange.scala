/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.util.{ OptionalLong, Optional }
import akka.http.impl.util.{ Rendering, ValueRenderable }
import akka.http.javadsl.{ model ⇒ jm }
import scala.compat.java8.OptionConverters._

sealed trait ContentRange extends jm.ContentRange with ValueRenderable {
  // default implementations to override
  def isSatisfiable: Boolean = false
  def isOther: Boolean = false
  def getSatisfiableFirst: OptionalLong = OptionalLong.empty()
  def getSatisfiableLast: OptionalLong = OptionalLong.empty()
  def getOtherValue: Optional[String] = Optional.empty()
}

sealed trait ByteContentRange extends ContentRange {
  def instanceLength: Option[Long]

  /** Java API */
  def isByteContentRange: Boolean = true
  /** Java API */
  def getInstanceLength: OptionalLong = instanceLength.asPrimitive
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

    /** Java API */
    override def isSatisfiable: Boolean = true
    /** Java API */
    override def getSatisfiableFirst: OptionalLong = OptionalLong.of(first)
    /** Java API */
    override def getSatisfiableLast: OptionalLong = OptionalLong.of(last)
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

    /** Java API */
    def isByteContentRange = false
    /** Java API */
    def getInstanceLength: OptionalLong = OptionalLong.empty()
    /** Java API */
    override def getOtherValue: Optional[String] = Optional.of(value)
  }
}
