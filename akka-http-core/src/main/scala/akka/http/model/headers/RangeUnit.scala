/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import akka.http.util.{ Rendering, ValueRenderable }

sealed abstract class RangeUnit extends japi.headers.RangeUnit with ValueRenderable {
  def name: String
}

object RangeUnits {
  object Bytes extends RangeUnit {
    def name = "Bytes"

    def render[R <: Rendering](r: R): r.type = r ~~ "bytes"
  }

  final case class Other(name: String) extends RangeUnit {
    def render[R <: Rendering](r: R): r.type = r ~~ name
  }
}
