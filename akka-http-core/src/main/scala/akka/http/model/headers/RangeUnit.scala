/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, ValueRenderable }

sealed trait RangeUnit extends ValueRenderable

object RangeUnit {
  object Bytes extends RangeUnit {
    def render[R <: Rendering](r: R): r.type = r ~~ "bytes"
  }

  final case class Other(name: String) extends RangeUnit {
    def render[R <: Rendering](r: R): r.type = r ~~ name
  }
}
