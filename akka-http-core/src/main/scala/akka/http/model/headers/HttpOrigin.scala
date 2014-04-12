/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import language.implicitConversions
import org.parboiled2.UTF8
import akka.http.model.parser.UriParser
import akka.http.util._

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
