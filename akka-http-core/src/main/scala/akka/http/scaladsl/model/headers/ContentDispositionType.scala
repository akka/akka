/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.util.{ Rendering, SingletonValueRenderable, Renderable }
import akka.http.javadsl.{ model ⇒ jm }

sealed trait ContentDispositionType extends Renderable with jm.headers.ContentDispositionType

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
