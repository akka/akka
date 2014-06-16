/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import akka.http.util.{ Rendering, SingletonValueRenderable, Renderable }

sealed trait ContentDispositionType extends Renderable with japi.headers.ContentDispositionType

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
