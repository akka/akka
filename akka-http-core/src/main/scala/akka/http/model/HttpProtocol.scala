/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.http.util.{ SingletonValueRenderable, ObjectRegistry }

/** The protocol of an HTTP message */
case class HttpProtocol private[http] (override val value: String) extends SingletonValueRenderable

object HttpProtocols extends ObjectRegistry[String, HttpProtocol] {
  private def register(p: HttpProtocol): HttpProtocol = register(p.value, p)

  val `HTTP/1.0` = register(HttpProtocol("HTTP/1.0"))
  val `HTTP/1.1` = register(HttpProtocol("HTTP/1.1"))
}
