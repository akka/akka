/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import scala.collection.immutable
import akka.http.impl.util.{ Rendering, ValueRenderable }

/**
 * A websocket extension as defined in http://tools.ietf.org/html/rfc6455#section-4.3
 */
final case class WebSocketExtension(name: String, params: immutable.Map[String, String] = Map.empty) extends ValueRenderable {
  def render[R <: Rendering](r: R): r.type = {
    r ~~ name
    if (params.nonEmpty)
      params.foreach {
        case (k, "") ⇒ r ~~ "; " ~~ k
        case (k, v)  ⇒ r ~~ "; " ~~ k ~~ '=' ~~# v
      }
    r
  }
}
