/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import akka.http.util.{ Rendering, ValueRenderable }
import akka.japi.{ Option ⇒ JOption }
import java.{ lang ⇒ jl }

sealed abstract class ByteRange extends japi.headers.ByteRange with ValueRenderable {
  /** Java API */
  def getSliceFirst: JOption[jl.Long] = JOption.none
  /** Java API */
  def getSliceLast: JOption[jl.Long] = JOption.none
  /** Java API */
  def getOffset: JOption[jl.Long] = JOption.none
  /** Java API */
  def getSuffixLength: JOption[jl.Long] = JOption.none

  /** Java API */
  def isSlice: Boolean = false

  /** Java API */
  def isFromOffset: Boolean = false

  /** Java API */
  def isSuffix: Boolean = false
}

object ByteRange {
  def apply(first: Long, last: Long) = Slice(first, last)
  def fromOffset(offset: Long) = FromOffset(offset)
  def suffix(length: Long) = Suffix(length)

  final case class Slice(first: Long, last: Long) extends ByteRange {
    require(0 <= first && first <= last, "first must be >= 0 and <= last")
    def render[R <: Rendering](r: R): r.type = r ~~ first ~~ '-' ~~ last

    /** Java API */
    override def isSlice: Boolean = true
    /** Java API */
    override def getSliceFirst: JOption[jl.Long] = JOption.some(first)
    /** Java API */
    override def getSliceLast: JOption[jl.Long] = JOption.some(last)
  }

  final case class FromOffset(offset: Long) extends ByteRange {
    require(0 <= offset, "offset must be >= 0")
    def render[R <: Rendering](r: R): r.type = r ~~ offset ~~ '-'

    /** Java API */
    override def isFromOffset: Boolean = true
    /** Java API */
    override def getOffset: JOption[jl.Long] = JOption.some(offset)
  }

  final case class Suffix(length: Long) extends ByteRange {
    require(0 <= length, "length must be >= 0")
    def render[R <: Rendering](r: R): r.type = r ~~ '-' ~~ length

    /** Java API */
    override def isSuffix: Boolean = true
    /** Java API */
    override def getSuffixLength: JOption[jl.Long] = JOption.some(length)
  }
}