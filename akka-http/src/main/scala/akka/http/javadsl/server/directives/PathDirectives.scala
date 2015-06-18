/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure
import akka.http.impl.server.RouteStructure.{ RedirectToNoTrailingSlashIfPresent, RedirectToTrailingSlashIfMissing }
import akka.http.javadsl.model.StatusCode
import akka.http.javadsl.server.values.{ PathMatchers, PathMatcher }

import scala.annotation.varargs
import scala.collection.immutable

abstract class PathDirectives extends MiscDirectives {
  /**
   * Tries to consumes the complete unmatched path given a number of PathMatchers. Between each
   * of the matchers a `/` will be matched automatically.
   *
   * A matcher can either be a matcher of type `PathMatcher`, or a literal string.
   */
  @varargs
  def path(matchers: AnyRef*): Directive =
    RawPathPrefixForMatchers(joinWithSlash(convertMatchers(matchers)) :+ PathMatchers.END)

  @varargs
  def pathPrefix(matchers: AnyRef*): Directive =
    RawPathPrefixForMatchers(joinWithSlash(convertMatchers(matchers)))

  @varargs
  def pathPrefixTest(matchers: AnyRef*): Directive =
    RawPathPrefixTestForMatchers(joinWithSlash(convertMatchers(matchers)))

  @varargs
  def rawPathPrefix(matchers: AnyRef*): Directive =
    RawPathPrefixForMatchers(convertMatchers(matchers))

  @varargs
  def rawPathPrefixTest(matchers: AnyRef*): Directive =
    RawPathPrefixTestForMatchers(convertMatchers(matchers))

  @varargs
  def pathSuffix(matchers: AnyRef*): Directive =
    Directives.custom(RouteStructure.PathSuffix(convertMatchers(matchers)))

  @varargs
  def pathSuffixTest(matchers: AnyRef*): Directive =
    Directives.custom(RouteStructure.PathSuffixTest(convertMatchers(matchers)))

  def pathEnd: Directive = RawPathPrefixForMatchers(PathMatchers.END :: Nil)
  def pathSingleSlash: Directive = RawPathPrefixForMatchers(List(PathMatchers.SLASH, PathMatchers.END))
  def pathEndOrSingleSlash: Directive = RawPathPrefixForMatchers(List(PathMatchers.SLASH.optional, PathMatchers.END))

  @varargs
  def redirectToTrailingSlashIfMissing(redirectionStatusCode: StatusCode, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RedirectToTrailingSlashIfMissing(redirectionStatusCode)(innerRoute, moreInnerRoutes.toList)
  @varargs
  def redirectToNoTrailingSlashIfPresent(redirectionStatusCode: StatusCode, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RedirectToNoTrailingSlashIfPresent(redirectionStatusCode)(innerRoute, moreInnerRoutes.toList)

  private def RawPathPrefixForMatchers(matchers: immutable.Seq[PathMatcher[_]]): Directive =
    Directives.custom(RouteStructure.RawPathPrefix(matchers))

  private def RawPathPrefixTestForMatchers(matchers: immutable.Seq[PathMatcher[_]]): Directive =
    Directives.custom(RouteStructure.RawPathPrefixTest(matchers))

  private def joinWithSlash(matchers: immutable.Seq[PathMatcher[_]]): immutable.Seq[PathMatcher[_]] = {
    def join(result: immutable.Seq[PathMatcher[_]], next: PathMatcher[_]): immutable.Seq[PathMatcher[_]] =
      result :+ PathMatchers.SLASH :+ next

    matchers.foldLeft(immutable.Seq.empty[PathMatcher[_]])(join)
  }

  private def convertMatchers(matchers: Seq[AnyRef]): immutable.Seq[PathMatcher[_]] = {
    def parse(matcher: AnyRef): PathMatcher[_] = matcher match {
      case p: PathMatcher[_] ⇒ p
      case name: String      ⇒ PathMatchers.segment(name)
      case x                 ⇒ throw new IllegalArgumentException(s"Matcher of class ${x.getClass} is unsupported for PathDirectives")
    }

    matchers.map(parse).toVector
  }
}