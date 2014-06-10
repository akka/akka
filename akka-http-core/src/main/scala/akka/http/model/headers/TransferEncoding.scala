/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, SingletonValueRenderable, Renderable }

sealed trait TransferEncoding extends Renderable

object TransferEncoding {
  case object chunked extends TransferEncoding with SingletonValueRenderable
  case object compress extends TransferEncoding with SingletonValueRenderable
  case object deflate extends TransferEncoding with SingletonValueRenderable
  case object gzip extends TransferEncoding with SingletonValueRenderable
  final case class Extension(name: String, params: Map[String, String] = Map.empty) extends TransferEncoding {
    def render[R <: Rendering](r: R): r.type = {
      r ~~ name
      params foreach { case (k, v) ⇒ r ~~ "; " ~~ k ~~ '=' ~~# v }
      r
    }
  }
}
