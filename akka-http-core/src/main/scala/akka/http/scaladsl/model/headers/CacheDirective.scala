/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.annotation.ApiMayChange

import scala.annotation.{ tailrec, varargs }
import scala.collection.immutable
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }

sealed trait CacheDirective extends Renderable with jm.headers.CacheDirective {
  def value: String
}

object CacheDirective {
  sealed trait RequestDirective extends CacheDirective
  sealed trait ResponseDirective extends CacheDirective

  final case class CustomCacheDirective(name: String, content: Option[String])
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
    final def render[R <: Rendering](r: R): r.type =
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

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.1 and
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.8
   */
  final case class `max-age`(deltaSeconds: Long) extends RequestDirective with ResponseDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = r ~~ productPrefix ~~ '=' ~~ deltaSeconds
  }

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.2
   */
  final case class `max-stale`(deltaSeconds: Option[Long]) extends RequestDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = deltaSeconds match {
      case Some(s) ⇒ r ~~ productPrefix ~~ '=' ~~ s
      case None    ⇒ r ~~ productPrefix
    }
  }

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.3
   */
  final case class `min-fresh`(deltaSeconds: Long) extends RequestDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = r ~~ productPrefix ~~ '=' ~~ deltaSeconds
  }

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.4
   */
  case object `no-cache` extends SingletonValueRenderable with RequestDirective with ResponseDirective {
    def apply(fieldNames: String*): `no-cache` = new `no-cache`(immutable.Seq(fieldNames: _*))
  }

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.5 and
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.3
   */
  case object `no-store` extends SingletonValueRenderable with RequestDirective with ResponseDirective

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.6 and
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.4
   */
  case object `no-transform` extends SingletonValueRenderable with RequestDirective with ResponseDirective

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.1.7
   */
  case object `only-if-cached` extends SingletonValueRenderable with RequestDirective

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.1
   */
  case object `must-revalidate` extends SingletonValueRenderable with ResponseDirective

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.2
   */
  final case class `no-cache`(fieldNames: immutable.Seq[String]) extends FieldNamesDirective with ResponseDirective

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.5
   */
  case object `public` extends SingletonValueRenderable with ResponseDirective

  /** Java API */
  def getPublic: ResponseDirective = `public`

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.6
   */
  final case class `private`(fieldNames: immutable.Seq[String]) extends FieldNamesDirective with ResponseDirective
  object `private` {
    def apply(fieldNames: String*): `private` = new `private`(immutable.Seq(fieldNames: _*))
  }

  /** Java API */
  @varargs def createPrivate(fieldNames: String*): ResponseDirective = new `private`(immutable.Seq(fieldNames: _*))

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.7
   */
  case object `proxy-revalidate` extends SingletonValueRenderable with ResponseDirective

  /**
   * For a fuller description of the use case, see
   * http://tools.ietf.org/html/rfc7234#section-5.2.2.9
   */
  final case class `s-maxage`(deltaSeconds: Long) extends ResponseDirective with ValueRenderable {
    def render[R <: Rendering](r: R): r.type = r ~~ productPrefix ~~ '=' ~~ deltaSeconds
  }

  /** https://tools.ietf.org/html/rfc8246 */
  @ApiMayChange
  case object immutableDirective extends SingletonValueRenderable with ResponseDirective {
    private val valueBytes = "immutable".asciiBytes
    override def render[R <: Rendering](r: R): r.type = r ~~ valueBytes
  }

  /** Java API */
  def getImmutable: ResponseDirective = immutableDirective
}
