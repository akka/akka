/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.common.ToNameReceptacleEnhancements
import akka.http.model.StatusCodes
import akka.http.model.Uri.Path

trait PathDirectives extends PathMatchers with ImplicitPathMatcherConstruction with ToNameReceptacleEnhancements {
  import BasicDirectives._
  import RouteDirectives._
  import PathMatcher._

  /**
   * Consumes a leading slash from the unmatched path of the [[akka.http.server.RequestContext]]
   * before applying the given matcher. The matcher has to match the remaining path completely
   * or leave only a single trailing slash.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def path[L](pm: PathMatcher[L]): Directive[L] = pathPrefix(pm ~ PathEnd)

  /**
   * Consumes a leading slash from the unmatched path of the [[akka.http.server.RequestContext]]
   * before applying the given matcher. The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def pathPrefix[L](pm: PathMatcher[L]): Directive[L] = rawPathPrefix(Slash ~ pm)

  /**
   * Applies the given matcher directly to the unmatched path of the [[akka.http.server.RequestContext]]
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
   * Checks whether the unmatchedPath of the [[akka.http.server.RequestContext]] has a prefix matched by the
   * given PathMatcher. In analogy to the `pathPrefix` directive a leading slash is implied.
   */
  def pathPrefixTest[L](pm: PathMatcher[L]): Directive[L] = rawPathPrefixTest(Slash ~ pm)

  /**
   * Checks whether the unmatchedPath of the [[akka.http.server.RequestContext]] has a prefix matched by the
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
   * Rejects the request if the unmatchedPath of the [[akka.http.server.RequestContext]] does not have a suffix
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
   * Checks whether the unmatchedPath of the [[akka.http.server.RequestContext]] has a suffix matched by the
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
   * Rejects the request if the unmatchedPath of the [[akka.http.server.RequestContext]] is non-empty,
   * or said differently: only passes on the request to its inner route if the request path
   * has been matched completely.
   */
  def pathEnd: Directive0 = rawPathPrefix(PathEnd)

  /**
   * Only passes on the request to its inner route if the request path has been matched
   * completely or only consists of exactly one remaining slash.
   *
   * Note that trailing slash and non-trailing slash URLs are '''not''' the same, although they often serve
   * the same content. It is recommended to serve only one URL version and make the other redirect to it using
   * [[redirectTrailingSlashAppended]] or [[redirectTrailingSlashStripped]] directive.
   *
   * For example:
   * {{{
   * def route =
   *   redirectTrailingSlashStripped(Found) { // redirect '/users/' to '/users', '/users/:userId/' to '/users/:userId'
   *     pathPrefix("users") {
   *       pathEnd {
   *         // user list ...
   *       } ~
   *       path(UUID) { userId =>
   *         // user profile ...
   *       }
   *     }
   *   }
   * }}}
   *
   * For further information, refer to:
   * [[http://googlewebmastercentral.blogspot.de/2010/04/to-slash-or-not-to-slash.html]]
   */
  def pathEndOrSingleSlash: Directive0 = rawPathPrefix(Slash.? ~ PathEnd)

  /**
   * Only passes on the request to its inner route if the request path
   * consists of exactly one remaining slash.
   */
  def pathSingleSlash: Directive0 = pathPrefix(PathEnd)

  /**
   * If the request path doesn't end with a slash, redirect to the same uri with trailing slash in the path.
   *
   * '''Caveat''': [[path]] without trailing slash and [[pathEnd]] directives will not match inside of this directive.
   */
  def redirectTrailingSlashAppended(redirectionType: StatusCodes.Redirection): Directive0 =
    extractUri.flatMap { uri ⇒
      if (uri.path.reverse.startsWithSlash) pass
      else {
        val newPath = uri.path ++ Path.SingleSlash
        val newUri = uri.withPath(newPath)
        redirect(newUri, redirectionType)
      }
    }

  /**
   * If the request path ends with a slash, redirect to the same uri without trailing slash in the path.
   *
   * '''Caveat''': [[pathSingleSlash]] directive will not match inside of this directive.
   */
  def redirectTrailingSlashStripped(redirectionType: StatusCodes.Redirection): Directive0 =
    extractUri.flatMap { uri ⇒
      uri.path.reverse match {
        case Path.SingleSlash ⇒ pass
        case reversed if reversed.startsWithSlash ⇒
          val newPath = reversed.tail.reverse
          val newUri = uri.withPath(newPath)
          redirect(newUri, redirectionType)
        case _ ⇒ pass
      }
    }
}

object PathDirectives extends PathDirectives
