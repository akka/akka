/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import language.implicitConversions
import scala.collection.immutable
import org.parboiled2.UTF8
import akka.http.model.parser.UriParser
import akka.http.util._

abstract class HttpOriginRange extends ValueRenderable {
  def matches(origin: HttpOrigin): Boolean
}
object HttpOriginRange {
  implicit val originsRenderer: Renderer[Seq[HttpOrigin]] = Renderer.seqRenderer(" ", "null")

  case object `*` extends HttpOriginRange {
    def matches(origin: HttpOrigin) = true
    def render[R <: Rendering](r: R): r.type = r ~~ '*'
  }

  def apply(origins: HttpOrigin*): Default = Default(immutable.Seq(origins: _*))

  case class Default(origins: immutable.Seq[HttpOrigin]) extends HttpOriginRange {
    def matches(origin: HttpOrigin): Boolean = origins contains origin
    def render[R <: Rendering](r: R): r.type = r ~~ origins
  }
}

case class HttpOrigin(scheme: String, host: Host) extends ValueRenderable {
  def render[R <: Rendering](r: R): r.type = host.renderValue(r ~~ scheme ~~ "://")
}
object HttpOrigin {
  implicit def apply(str: String): HttpOrigin = {
    val parser = new UriParser(str, UTF8, Uri.ParsingMode.Relaxed)
    parser.parseOrigin()
  }
}