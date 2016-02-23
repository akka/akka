/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.impl.util.{ SingletonValueRenderable, ObjectRegistry }
import akka.http.javadsl.{ model ⇒ jm }

/** The protocol of an HTTP message */
final case class HttpProtocol private[http] (override val value: String) extends jm.HttpProtocol with SingletonValueRenderable

object HttpProtocols extends ObjectRegistry[String, HttpProtocol] {
  private def register(p: HttpProtocol): HttpProtocol = register(p.value, p)

  val `HTTP/1.0` = register(HttpProtocol("HTTP/1.0"))
  val `HTTP/1.1` = register(HttpProtocol("HTTP/1.1"))
}
