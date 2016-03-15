/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
   * Applies the given PathMatchers to the remaining unmatched path after consuming a leading slash.
   * The matcher has to match the remaining path completely.
   * If matched the value extracted by the PathMatchers is extracted on the directive level.
   *
   * Each of Each of the arguments s must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def path(matchers: AnyRef*): Directive =
    RawPathPrefixForMatchers(joinWithSlash(convertMatchers(matchers)) :+ PathMatchers.END)

  /**
   * Applies the given PathMatchers to a prefix of the remaining unmatched path after consuming a leading slash.
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   *
   * Each of the arguments  must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def pathPrefix(matchers: AnyRef*): Directive =
    RawPathPrefixForMatchers(joinWithSlash(convertMatchers(matchers)))

  /**
   * Checks whether the unmatchedPath of the [[akka.http.javadsl.server.RequestContext]] has a prefix matched by the
   * given PathMatcher. In analogy to the `pathPrefix` directive a leading slash is implied.
   *
   * Each of the arguments  must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def pathPrefixTest(matchers: AnyRef*): Directive =
    RawPathPrefixTestForMatchers(joinWithSlash(convertMatchers(matchers)))

  /**
   * Applies the given matcher directly to a prefix of the unmatched path of the
   * [[akka.http.javadsl.server.RequestContext]] (i.e. without implicitly consuming a leading slash).
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   *
   * Each of the arguments  must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def rawPathPrefix(matchers: AnyRef*): Directive =
    RawPathPrefixForMatchers(convertMatchers(matchers))

  /**
   * Checks whether the unmatchedPath of the [[akka.http.javadsl.server.RequestContext]] has a prefix matched by the
   * given PathMatcher. However, as opposed to the `pathPrefix` directive the matched path is not
   * actually "consumed".
   *
   * Each of the arguments  must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def rawPathPrefixTest(matchers: AnyRef*): Directive =
    RawPathPrefixTestForMatchers(convertMatchers(matchers))

  /**
   * Applies the given PathMatchers to a suffix of the remaining unmatchedPath of the [[akka.http.javadsl.server.RequestContext]].
   * If matched the value extracted by the PathMatchers is extracted and the matched parts of the path are consumed.
   * Note that, for efficiency reasons, the given PathMatchers must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffix("baz" / "bar")` would match `/foo/bar/baz`!
   *
   * Each of the arguments  must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def pathSuffix(matchers: AnyRef*): Directive =
    Directives.custom(RouteStructure.PathSuffix(convertMatchers(matchers)))

  /**
   * Checks whether the unmatchedPath of the [[akka.http.javadsl.server.RequestContext]] has a suffix matched by the
   * given PathMatcher. However, as opposed to the pathSuffix directive the matched path is not
   * actually "consumed".
   * Note that, for efficiency reasons, the given PathMatcher must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffixTest("baz" / "bar")` would match `/foo/bar/baz`!
   *
   * Each of the arguments  must either be an instance of [[akka.http.javadsl.server.values.PathMatcher]] or a constant String
   * that will be automatically converted using `PathMatcher.segment`.
   */
  @varargs
  def pathSuffixTest(matchers: AnyRef*): Directive =
    Directives.custom(RouteStructure.PathSuffixTest(convertMatchers(matchers)))

  /**
   * Rejects the request if the unmatchedPath of the [[akka.http.javadsl.server.RequestContext]] is non-empty,
   * or said differently: only passes on the request to its inner route if the request path
   * has been matched completely.
   */
  def pathEnd: Directive = RawPathPrefixForMatchers(PathMatchers.END :: Nil)

  /**
   * Only passes on the request to its inner route if the request path has been matched
   * completely or only consists of exactly one remaining slash.
   *
   * Note that trailing slash and non-trailing slash URLs are '''not''' the same, although they often serve
   * the same content. It is recommended to serve only one URL version and make the other redirect to it using
   * [[#redirectToTrailingSlashIfMissing]] or [[#redirectToNoTrailingSlashIfPresent]] directive.
   *
   * For example:
   * {{{
   * def route = {
   *   // redirect '/users/' to '/users', '/users/:userId/' to '/users/:userId'
   *   redirectToNoTrailingSlashIfPresent(Found) {
   *     pathPrefix("users") {
   *       pathEnd {
   *         // user list ...
   *       } ~
   *       path(UUID) { userId =>
   *         // user profile ...
   *       }
   *     }
   *   }
   * }
   * }}}
   *
   * For further information, refer to: http://googlewebmastercentral.blogspot.de/2010/04/to-slash-or-not-to-slash.html
   */
  def pathEndOrSingleSlash: Directive = RawPathPrefixForMatchers(List(PathMatchers.SLASH.optional, PathMatchers.END))

  /**
   * Only passes on the request to its inner route if the request path
   * consists of exactly one remaining slash.
   */
  def pathSingleSlash: Directive = RawPathPrefixForMatchers(List(PathMatchers.SLASH, PathMatchers.END))

  /**
   * If the request path doesn't end with a slash, redirect to the same uri with trailing slash in the path.
   *
   * '''Caveat''': [[#path]] without trailing slash and [[#pathEnd]] directives will not match inside of this directive.
   */
  @varargs
  def redirectToTrailingSlashIfMissing(redirectionStatusCode: StatusCode, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RedirectToTrailingSlashIfMissing(redirectionStatusCode)(innerRoute, moreInnerRoutes.toList)

  /**
   * If the request path ends with a slash, redirect to the same uri without trailing slash in the path.
   *
   * '''Caveat''': [[#pathSingleSlash]] directive will not match inside of this directive.
   */
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