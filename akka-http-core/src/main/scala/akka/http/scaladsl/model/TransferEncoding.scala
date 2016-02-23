/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.impl.util.{ Rendering, SingletonValueRenderable, Renderable }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

sealed abstract class TransferEncoding extends jm.TransferEncoding with Renderable {
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
