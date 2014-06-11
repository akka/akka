/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, SingletonValueRenderable, Renderable }

sealed trait ContentDispositionType extends Renderable

object ContentDispositionTypes {
  protected abstract class Predefined extends ContentDispositionType with SingletonValueRenderable {
    def name: String = value
  }

  case object inline extends Predefined
  case object attachment extends Predefined
  case object `form-data` extends Predefined
  final case class Ext(name: String) extends ContentDispositionType {
    def render[R <: Rendering](r: R): r.type = r ~~ name
  }
}
