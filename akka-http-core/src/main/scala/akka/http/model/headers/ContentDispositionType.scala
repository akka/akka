/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, SingletonValueRenderable, Renderable }

sealed trait ContentDispositionType extends Renderable

object ContentDispositionType {
  case object inline extends ContentDispositionType with SingletonValueRenderable
  case object attachment extends ContentDispositionType with SingletonValueRenderable
  case object `form-data` extends ContentDispositionType with SingletonValueRenderable
  case class Ext(name: String) extends ContentDispositionType {
    def render[R <: Rendering](r: R): r.type = r ~~ name
  }
}
