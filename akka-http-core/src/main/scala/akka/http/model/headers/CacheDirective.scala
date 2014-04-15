/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import scala.annotation.tailrec
import scala.collection.immutable
import akka.http.util._

sealed trait CacheDirective extends Renderable {
  def value: String
}

object CacheDirective {
  sealed trait RequestDirective extends CacheDirective
  sealed trait ResponseDirective extends CacheDirective

  private case class CustomCacheDirective(name: String, content: Option[String])
    extends RequestDirective with ResponseDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = content match {
      case Some(s) ⇒ r ~~ name ~~ '=' ~~# s
      case None    ⇒ r ~~ name
    }
  }

  def custom(name: String, content: Option[String]): RequestDirective with ResponseDirective =
    CustomCacheDirective(name, content)

  sealed abstract class FieldNamesDirective extends Product with ValueRenderable {
    def fieldNames: immutable.Seq[String]
    def render[R <: Rendering](r: R): r.type =
      if (fieldNames.nonEmpty) {
        r ~~ productPrefix ~~ '=' ~~ '"'
        @tailrec def rec(i: Int = 0): r.type =
          if (i < fieldNames.length) {
            if (i > 0) r ~~ ','
            r.putEscaped(fieldNames(i))
            rec(i + 1)
          } else r ~~ '"'
        rec()
      } else r ~~ productPrefix
  }
}

object CacheDirectives {
  import CacheDirective._

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.1
  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.8
  case class `max-age`(deltaSeconds: Long) extends RequestDirective with ResponseDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = r ~~ productPrefix ~~ '=' ~~ deltaSeconds
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.2
  case class `max-stale`(deltaSeconds: Option[Long]) extends RequestDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = deltaSeconds match {
      case Some(s) ⇒ r ~~ productPrefix ~~ '=' ~~ s
      case None    ⇒ r ~~ productPrefix
    }
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.3
  case class `min-fresh`(deltaSeconds: Long) extends RequestDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = r ~~ productPrefix ~~ '=' ~~ deltaSeconds
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.4
  case object `no-cache` extends SingletonValueRenderable with RequestDirective with ResponseDirective {
    def apply(fieldNames: String*): `no-cache` = apply(immutable.Seq(fieldNames: _*))
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.5
  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.3
  case object `no-store` extends SingletonValueRenderable with RequestDirective with ResponseDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.6
  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.4
  case object `no-transform` extends SingletonValueRenderable with RequestDirective with ResponseDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.1.7
  case object `only-if-cached` extends SingletonValueRenderable with RequestDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.1
  case object `must-revalidate` extends SingletonValueRenderable with ResponseDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.2
  case class `no-cache`(fieldNames: immutable.Seq[String]) extends FieldNamesDirective with ResponseDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.5
  case object `public` extends SingletonValueRenderable with ResponseDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.6
  case class `private`(fieldNames: immutable.Seq[String]) extends FieldNamesDirective with ResponseDirective
  object `private` {
    def apply(fieldNames: String*): `private` = apply(immutable.Seq(fieldNames: _*))
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.7
  case object `proxy-revalidate` extends SingletonValueRenderable with ResponseDirective

  // http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2.2.9
  case class `s-maxage`(deltaSeconds: Long) extends ResponseDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = r ~~ productPrefix ~~ '=' ~~ deltaSeconds
  }
}
