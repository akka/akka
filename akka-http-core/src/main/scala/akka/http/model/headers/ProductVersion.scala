package akka.http.model.headers

import akka.http.util._

case class ProductVersion(product: String = "", version: String = "", comment: String = "") extends ValueRenderable {
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
  implicit val productsRenderer: Renderer[Seq[ProductVersion]] = Renderer.seqRenderer[ProductVersion](separator = " ")

  // FIXME: once parsers have been migrated
  /** parses a string of multiple ProductVersions */
  def parseMultiple(string: String): Seq[ProductVersion] = ???
}
