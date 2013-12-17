package akka.http.model

import akka.http.rendering.SingletonValueRenderable

/** The protocol of an HTTP message */
case class HttpProtocol private[http] (override val value: String) extends SingletonValueRenderable

object HttpProtocols extends ObjectRegistry[String, HttpProtocol] {
  private def register(p: HttpProtocol): HttpProtocol = register(p.value, p)

  val `HTTP/1.0` = register(HttpProtocol("HTTP/1.0"))
  val `HTTP/1.1` = register(HttpProtocol("HTTP/1.1"))
}
