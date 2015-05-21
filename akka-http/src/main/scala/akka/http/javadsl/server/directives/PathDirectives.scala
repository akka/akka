/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure

import scala.annotation.varargs
import scala.collection.immutable

abstract class PathDirectives extends MethodDirectives {
  /**
   * Tries to consumes the complete unmatched path given a number of PathMatchers. Between each
   * of the matchers a `/` will be matched automatically.
   *
   * A matcher can either be a matcher of type `PathMatcher`, or a literal string.
   */
  @varargs
  def path(matchers: AnyRef*): Directive =
    forMatchers(joinWithSlash(convertMatchers(matchers)) :+ PathMatchers.END)

  @varargs
  def pathPrefix(matchers: AnyRef*): Directive =
    forMatchers(joinWithSlash(convertMatchers(matchers)))

  def pathSingleSlash: Directive = forMatchers(List(PathMatchers.SLASH, PathMatchers.END))

  @varargs
  def rawPathPrefix(matchers: AnyRef*): Directive =
    forMatchers(convertMatchers(matchers))

  private def forMatchers(matchers: immutable.Seq[PathMatcher[_]]): Directive =
    Directives.custom(RouteStructure.RawPathPrefix(matchers, _))

  private def joinWithSlash(matchers: immutable.Seq[PathMatcher[_]]): immutable.Seq[PathMatcher[_]] = {
    def join(result: immutable.Seq[PathMatcher[_]], next: PathMatcher[_]): immutable.Seq[PathMatcher[_]] =
      result :+ PathMatchers.SLASH :+ next

    matchers.foldLeft(immutable.Seq.empty[PathMatcher[_]])(join)
  }

  private def convertMatchers(matchers: Seq[AnyRef]): immutable.Seq[PathMatcher[_]] = {
    def parse(matcher: AnyRef): PathMatcher[_] = matcher match {
      case p: PathMatcher[_] ⇒ p
      case name: String      ⇒ PathMatchers.segment(name)
    }

    matchers.map(parse).toVector
  }
}