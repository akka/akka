/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import scala.collection.immutable
import scala.util.{ Failure, Success }
import akka.parboiled2.ParseError
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.model.parser.HeaderParser
import akka.http.impl.util._

final case class ProductVersion(product: String = "", version: String = "", comment: String = "") extends jm.headers.ProductVersion with ValueRenderable {
  def render[R <: Rendering](r: R): r.type = {
    r ~~ product
    if (!version.isEmpty) r ~~ '/' ~~ version
    if (!comment.isEmpty) {
      if (!product.isEmpty || !version.isEmpty) r ~~ ' '
      r ~~ '(' ~~ comment ~~ ')'
    }
    r
  }
}

object ProductVersion {
  implicit val productsRenderer: Renderer[immutable.Seq[ProductVersion]] = Renderer.seqRenderer[ProductVersion](separator = " ")

  /** parses a string of multiple ProductVersions */
  def parseMultiple(string: String): immutable.Seq[ProductVersion] = {
    val parser = new HeaderParser(string)
    def fail(msg: String) = throw new IllegalArgumentException(s"'$string' is not a legal sequence of ProductVersions: $msg")
    parser.products.run() match {
      case Success(x)             ⇒ immutable.Seq(x: _*)
      case Failure(e: ParseError) ⇒ fail(parser.formatError(e))
      case Failure(e)             ⇒ fail(e.getMessage)
    }
  }
}
