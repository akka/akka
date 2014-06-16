/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.{ lang ⇒ jl }

import akka.http.util.{ Rendering, ValueRenderable }

import akka.http.model.japi.JavaMapping.Implicits._

sealed trait ContentRange extends japi.ContentRange with ValueRenderable {
  // default implementations to override
  def isSatisfiable: Boolean = false
  def isOther: Boolean = false
  def getSatisfiableFirst: akka.japi.Option[jl.Long] = akka.japi.Option.none
  def getSatisfiableLast: akka.japi.Option[jl.Long] = akka.japi.Option.none
  def getOtherValue: akka.japi.Option[String] = akka.japi.Option.none
}

sealed trait ByteContentRange extends ContentRange {
  def instanceLength: Option[Long]

  /** Java API */
  def isByteContentRange: Boolean = true
  /** Java API */
  def getInstanceLength: akka.japi.Option[jl.Long] = instanceLength.asJava
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
    override def getSatisfiableFirst: akka.japi.Option[jl.Long] = akka.japi.Option.some(first)
    /** Java API */
    override def getSatisfiableLast: akka.japi.Option[jl.Long] = akka.japi.Option.some(last)
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
    def getInstanceLength: akka.japi.Option[jl.Long] = akka.japi.Option.none
    /** Java API */
    override def getOtherValue: akka.japi.Option[String] = akka.japi.Option.some(value)
  }
}