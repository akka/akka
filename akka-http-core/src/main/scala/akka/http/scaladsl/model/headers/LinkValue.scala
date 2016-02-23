/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import scala.collection.immutable
import akka.parboiled2.CharPredicate
import akka.http.impl.util._
import akka.http.scaladsl.model._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._
import UriRendering.UriRenderer

final case class LinkValue(uri: Uri, params: immutable.Seq[LinkParam]) extends jm.headers.LinkValue with ValueRenderable {
  def render[R <: Rendering](r: R): r.type = {
    r ~~ '<' ~~ uri ~~ '>'
    if (params.nonEmpty) r ~~ "; " ~~ params
    r
  }

  def getUri: jm.Uri = uri.asJava
  def getParams: java.lang.Iterable[jm.headers.LinkParam] = params.asJava
}

object LinkValue {
  def apply(uri: Uri, params: LinkParam*): LinkValue = apply(uri, immutable.Seq(params: _*))
}

sealed abstract class LinkParam extends jm.headers.LinkParam with ToStringRenderable {
  val key: String = getClass.getSimpleName
  def value: AnyRef
}
object LinkParam {
  implicit val paramsRenderer: Renderer[immutable.Seq[LinkParam]] = Renderer.seqRenderer(separator = "; ")
}

object LinkParams {
  private val reserved = CharPredicate(" ,;")

  // A few convenience rels
  val next = rel("next")
  val prev = rel("prev")
  val first = rel("first")
  val last = rel("last")
  val blockedBy = rel("blocked-by")

  // http://tools.ietf.org/html/rfc5988#section-5.3
  // can be either a bare word, an absolute URI, or a quoted, space-separated string of zero-or-more of either.
  final case class rel(value: String) extends LinkParam {
    def render[R <: Rendering](r: R): r.type = {
      r ~~ "rel="
      if (reserved matchesAny value) r ~~ '"' ~~ value ~~ '"' else r ~~ value
    }
  }

  // http://tools.ietf.org/html/rfc5988#section-5.2
  final case class anchor(uri: Uri) extends LinkParam {
    def value: AnyRef = uri

    def render[R <: Rendering](r: R): r.type = r ~~ "anchor=\"" ~~ uri ~~ '"'
  }

  // http://tools.ietf.org/html/rfc5988#section-5.3
  // can be either a bare word, an absolute URI, or a quoted, space-separated string of zero-or-more of either.
  final case class rev(value: String) extends LinkParam {
    def render[R <: Rendering](r: R): r.type = {
      r ~~ "rev="
      if (reserved matchesAny value) r ~~ '"' ~~ value ~~ '"' else r ~~ value
    }
  }

  // http://tools.ietf.org/html/rfc5988#section-5.4
  final case class hreflang(lang: Language) extends LinkParam {
    def value: AnyRef = lang

    def render[R <: Rendering](r: R): r.type = r ~~ "hreflang=" ~~ lang
  }

  // http://tools.ietf.org/html/rfc5988#section-5.4
  final case class media(desc: String) extends LinkParam {
    def value: AnyRef = desc

    def render[R <: Rendering](r: R): r.type = {
      r ~~ "media="
      if (reserved matchesAny desc) r ~~ '"' ~~ desc ~~ '"' else r ~~ desc
    }
  }

  // http://tools.ietf.org/html/rfc5988#section-5.4
  final case class title(title: String) extends LinkParam {
    def value: AnyRef = title

    def render[R <: Rendering](r: R): r.type = r ~~ "title=\"" ~~ title ~~ '"'
  }

  // http://tools.ietf.org/html/rfc5988#section-5.4
  final case class `title*`(title: String) extends LinkParam {
    def value: AnyRef = title

    def render[R <: Rendering](r: R): r.type = {
      r ~~ "title*="
      if (reserved matchesAny title) r ~~ '"' ~~ title ~~ '"' else r ~~ title
    }
  }

  // http://tools.ietf.org/html/rfc5988#section-5.4
  final case class `type`(mediaType: MediaType) extends LinkParam {
    def value: AnyRef = mediaType

    def render[R <: Rendering](r: R): r.type = {
      r ~~ "type="
      if (reserved matchesAny mediaType.value) r ~~ '"' ~~ mediaType.value ~~ '"' else r ~~ mediaType.value
    }
  }
}
