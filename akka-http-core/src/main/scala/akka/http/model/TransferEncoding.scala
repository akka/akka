/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.http.util.{ Rendering, SingletonValueRenderable, Renderable }

import akka.http.model.japi.JavaMapping.Implicits._

sealed abstract class TransferEncoding extends japi.TransferEncoding with Renderable {
  def name: String
  def params: Map[String, String]

  def getParams: java.util.Map[String, String] = params.asJava
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
