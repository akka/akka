/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.model.JavaInitialization
import akka.util.Unsafe

import language.implicitConversions
import scala.collection.immutable
import akka.parboiled2.UTF8
import akka.http.impl.model.parser.UriParser
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.model.Uri
import akka.http.impl.util.JavaMapping.Implicits._

abstract class HttpOriginRange extends jm.headers.HttpOriginRange with ValueRenderable {
  def matches(origin: HttpOrigin): Boolean

  /** Java API */
  def matches(origin: jm.headers.HttpOrigin): Boolean = matches(origin.asScala)
}
object HttpOriginRange {
  case object `*` extends HttpOriginRange {
    def matches(origin: HttpOrigin) = true
    def render[R <: Rendering](r: R): r.type = r ~~ '*'
  }

  def apply(origins: HttpOrigin*): Default = Default(immutable.Seq(origins: _*))

  final case class Default(origins: immutable.Seq[HttpOrigin]) extends HttpOriginRange {
    def matches(origin: HttpOrigin): Boolean = origins contains origin
    def render[R <: Rendering](r: R): r.type = r ~~ origins
  }

  JavaInitialization.initializeStaticFieldWith(
    `*`, classOf[jm.headers.HttpOriginRange].getField("ALL"))

}

final case class HttpOrigin(scheme: String, host: Host) extends jm.headers.HttpOrigin with ValueRenderable {
  def render[R <: Rendering](r: R): r.type = host.renderValue(r ~~ scheme ~~ "://")
}
object HttpOrigin {
  implicit val originsRenderer: Renderer[immutable.Seq[HttpOrigin]] = Renderer.seqRenderer(" ", "null")

  implicit def apply(str: String): HttpOrigin = {
    val parser = new UriParser(str, UTF8, Uri.ParsingMode.Relaxed)
    parser.parseOrigin()
  }
}