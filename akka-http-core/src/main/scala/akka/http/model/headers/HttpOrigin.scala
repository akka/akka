package akka.http.model
package headers

import scala.language.implicitConversions

import akka.http.util._
import akka.http.rendering._
import parser.UriParser

case class HttpOrigin(scheme: String, host: Host) extends ToStringRenderable {
  def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme ~~ "://" ~~ host.host
    if (host.port != 0) r ~~ ":" ~~ host.port
    else r
  }
}
object HttpOrigin {
  implicit def apply(str: String): HttpOrigin = {
    val parser = new UriParser(str, UTF8, Uri.ParsingMode.Relaxed)
    parser.parseOrigin()
  }

  implicit val originListRenderer: Renderer[Seq[HttpOrigin]] =
    Renderer.seqRenderer(" ", "null")
}

sealed trait AllowedOrigins extends ToStringRenderable
case object AllOrigins extends AllowedOrigins {
  def render[R <: Rendering](r: R): r.type = r ~~ '*'
}
case class SomeOrigins(originList: Seq[HttpOrigin]) extends AllowedOrigins {
  def render[R <: Rendering](r: R): r.type = r ~~ originList
}
