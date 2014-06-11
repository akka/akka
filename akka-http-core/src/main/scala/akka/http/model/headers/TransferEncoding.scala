/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import akka.http.util.{ Rendering, SingletonValueRenderable, Renderable }

sealed trait TransferEncoding extends Renderable {
  def name: String
  def params: Map[String, String]
}

object TransferEncodings {
  protected abstract class Predefined extends TransferEncoding with SingletonValueRenderable {
    def name: String = value
    def params: Map[String, String] = Map.empty
  }

  case object chunked extends Predefined
  case object compress extends Predefined
  case object deflate extends Predefined
  case object gzip extends Predefined
  final case class Extension(name: String, params: Map[String, String] = Map.empty) extends TransferEncoding {
    def render[R <: Rendering](r: R): r.type = {
      r ~~ name
      params foreach { case (k, v) ⇒ r ~~ "; " ~~ k ~~ '=' ~~# v }
      r
    }
  }
}
