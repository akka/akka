/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, ValueRenderable }

sealed trait ByteRange extends ValueRenderable

object ByteRange {
  def apply(first: Long, last: Long) = Slice(first, last)
  def fromOffset(offset: Long) = FromOffset(offset)
  def suffix(length: Long) = Suffix(length)

  final case class Slice(first: Long, last: Long) extends ByteRange {
    require(0 <= first && first <= last, "first must be >= 0 and <= last")
    def render[R <: Rendering](r: R): r.type = r ~~ first ~~ '-' ~~ last
  }

  final case class FromOffset(offset: Long) extends ByteRange {
    require(0 <= offset, "offset must be >= 0")
    def render[R <: Rendering](r: R): r.type = r ~~ offset ~~ '-'
  }

  final case class Suffix(length: Long) extends ByteRange {
    require(0 <= length, "length must be >= 0")
    def render[R <: Rendering](r: R): r.type = r ~~ '-' ~~ length
  }
}