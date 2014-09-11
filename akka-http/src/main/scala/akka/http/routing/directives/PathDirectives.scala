/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package directives

trait PathDirectives extends PathMatchers with ImplicitPathMatcherConstruction with ToNameReceptacleEnhancements {
  import BasicDirectives._
  import RouteDirectives._
  import PathMatcher._

  /**
   * Consumes a leading slash from the unmatched path of the [[akka.http.routing.RequestContext]]
   * before applying the given matcher. The matcher has to match the remaining path completely
   * or leave only a single trailing slash.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def path[L](pm: PathMatcher[L]): Directive[L] = pathPrefix(pm ~ PathEnd)

  /**
   * Consumes a leading slash from the unmatched path of the [[akka.http.routing.RequestContext]]
   * before applying the given matcher. The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def pathPrefix[L](pm: PathMatcher[L]): Directive[L] = rawPathPrefix(Slash ~ pm)

  /**
   * Applies the given matcher directly to the unmatched path of the [[akka.http.routing.RequestContext]]
   * (i.e. without implicitly consuming a leading slash).
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def rawPathPrefix[L](pm: PathMatcher[L]): Directive[L] = {
    implicit def LIsTuple = pm.ev
    extract(ctx ⇒ pm(ctx.unmatchedPath)).flatMap {
      case Matched(rest, values) ⇒ tprovide(values) & mapRequestContext(_ withUnmatchedPath rest)
      case Unmatched             ⇒ reject
    }
  }

  /**
   * Checks whether the unmatchedPath of the [[akka.http.routing.RequestContext]] has a prefix matched by the
   * given PathMatcher. In analogy to the `pathPrefix` directive a leading slash is implied.
   */
  def pathPrefixTest[L](pm: PathMatcher[L]): Directive[L] = rawPathPrefixTest(Slash ~ pm)

  /**
   * Checks whether the unmatchedPath of the [[akka.http.routing.RequestContext]] has a prefix matched by the
   * given PathMatcher. However, as opposed to the `pathPrefix` directive the matched path is not
   * actually "consumed".
   */
  def rawPathPrefixTest[L](pm: PathMatcher[L]): Directive[L] = {
    implicit def LIsTuple = pm.ev
    extract(ctx ⇒ pm(ctx.unmatchedPath)).flatMap {
      case Matched(_, values) ⇒ tprovide(values)
      case Unmatched          ⇒ reject
    }
  }

  /**
   * Rejects the request if the unmatchedPath of the [[akka.http.routing.RequestContext]] does not have a suffix
   * matched the given PathMatcher. If matched the value extracted by the PathMatcher is extracted
   * and the matched parts of the path are consumed.
   * Note that, for efficiency reasons, the given PathMatcher must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffix("baz" / "bar")` would match `/foo/bar/baz`!
   */
  def pathSuffix[L](pm: PathMatcher[L]): Directive[L] = {
    implicit def LIsTuple = pm.ev
    extract(ctx ⇒ pm(ctx.unmatchedPath.reverse)).flatMap {
      case Matched(rest, values) ⇒ tprovide(values) & mapRequestContext(_.withUnmatchedPath(rest.reverse))
      case Unmatched             ⇒ reject
    }
  }

  /**
   * Checks whether the unmatchedPath of the [[akka.http.routing.RequestContext]] has a suffix matched by the
   * given PathMatcher. However, as opposed to the pathSuffix directive the matched path is not
   * actually "consumed".
   * Note that, for efficiency reasons, the given PathMatcher must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffixTest("baz" / "bar")` would match `/foo/bar/baz`!
   */
  def pathSuffixTest[L](pm: PathMatcher[L]): Directive[L] = {
    implicit def LIsTuple = pm.ev
    extract(ctx ⇒ pm(ctx.unmatchedPath.reverse)).flatMap {
      case Matched(_, values) ⇒ tprovide(values)
      case Unmatched          ⇒ reject
    }
  }

  /**
   * Rejects the request if the unmatchedPath of the [[akka.http.routing.RequestContext]] is non-empty,
   * or said differently: only passes on the request to its inner route if the request path
   * has been matched completely.
   */
  def pathEnd: Directive0 = rawPathPrefix(PathEnd)

  /**
   * Only passes on the request to its inner route if the request path has been matched
   * completely or only consists of exactly one remaining slash.
   */
  def pathEndOrSingleSlash: Directive0 = rawPathPrefix(Slash.? ~ PathEnd)

  /**
   * Only passes on the request to its inner route if the request path
   * consists of exactly one remaining slash.
   */
  def pathSingleSlash: Directive0 = pathPrefix(PathEnd)
}

object PathDirectives extends PathDirectives