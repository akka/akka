/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.util.Optional
import java.util.function.Supplier

import scala.compat.java8.OptionConverters._

import akka.http.javadsl.model.DateTime
import akka.http.javadsl.model.headers.EntityTag
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class CacheConditionDirectives extends BasicDirectives {
  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   */
  def conditional(eTag: EntityTag, inner: Supplier[Route]): Route = ScalaRoute {
    D.conditional(eTag) { inner.get.toScala }
  }

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   */
  def conditional(lastModified: DateTime, inner: Supplier[Route]): Route = ScalaRoute {
    D.conditional(lastModified) { inner.get.toScala }
  }

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   */
  def conditional(eTag: EntityTag, lastModified: DateTime, inner: Supplier[Route]): Route = ScalaRoute {
    D.conditional(eTag, lastModified) { inner.get.toScala }
  }

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   */
  def conditional(eTag: Optional[EntityTag], lastModified: Optional[DateTime], inner: Supplier[Route]): Route = ScalaRoute {
    D.conditional(eTag.asScala, lastModified.asScala) { inner.get.toScala }
  }

}
