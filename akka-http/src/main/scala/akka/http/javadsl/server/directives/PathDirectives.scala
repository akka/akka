/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.function.BiFunction
import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Supplier

import scala.util.Failure
import scala.util.Success

import akka.http.javadsl.model.StatusCode
import akka.http.javadsl.server.PathMatcher0
import akka.http.javadsl.server.PathMatcher1
import akka.http.javadsl.server.PathMatcher2
import akka.http.javadsl.server.Route
import akka.http.javadsl.server.Unmarshaller
import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.server.{ Directives ⇒ D }

import akka.http.scaladsl.server.PathMatchers

/**
 * Only path prefixes are matched by these methods, since any kind of chaining path extractions
 * in Java would just look cumbersome without operator overloads; hence, no PathMatcher for Java.
 *
 * Just nest path() directives with the required types, ending in pathEnd() if you want to fail
 * further paths.
 */
abstract class PathDirectives extends ParameterDirectives {

  /**
   * Rejects the request if the unmatchedPath of the [[akka.http.javadsl.server.RequestContext]] is non-empty,
   * or said differently: only passes on the request to its inner route if the request path
   * has been matched completely.
   */
  def pathEnd(inner: Supplier[Route]): Route = ScalaRoute(
    D.pathEnd { inner.get.toScala })

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
  def pathEndOrSingleSlash(inner: Supplier[Route]): Route = ScalaRoute {
    D.pathEndOrSingleSlash { inner.get.toScala }
  }

  /**
   * Only passes on the request to its inner route if the request path
   * consists of exactly one remaining slash.
   */
  def pathSingleSlash(inner: Supplier[Route]): Route = ScalaRoute {
    D.pathSingleSlash { inner.get.toScala }
  }

  /**
   * Applies the given [[PathMatcher]] to the remaining unmatched path after consuming a leading slash.
   * The matcher has to match the remaining path completely.
   * If matched the value extracted by the [[PathMatcher]] is extracted on the directive level.
   */
  def path(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.path(segment) { inner.get.toScala }
  }
  def path(inner: java.util.function.Function[String, Route]): Route = ScalaRoute {
    D.path(PathMatchers.Segment) { element ⇒ inner.apply(element).toScala }
  }
  def path(p: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.path(p.toScala) { inner.get.toScala }
  }
  def path[T](p: PathMatcher1[T], inner: JFunction[T, Route]): Route = ScalaRoute {
    D.path(p.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def path[T1, T2](p: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.path(p.toScala) { (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * Applies the given [[PathMatcher]] to a prefix of the remaining unmatched path after consuming a leading slash.
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def pathPrefix(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathPrefix(segment) { inner.get.toScala }
  }
  def pathPrefix(inner: java.util.function.Function[String, Route]): Route = ScalaRoute {
    D.pathPrefix(PathMatchers.Segment) { element ⇒ inner.apply(element).toScala }
  }
  def pathPrefix(p: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathPrefix(p.toScala) { inner.get.toScala }
  }
  def pathPrefix[T](p: PathMatcher1[T], inner: JFunction[T, Route]): Route = ScalaRoute {
    D.pathPrefix(p.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def pathPrefix[T1, T2](p: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.pathPrefix(p.toScala) { (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * Applies the given matcher directly to a prefix of the unmatched path of the
   * [[RequestContext]] (i.e. without implicitly consuming a leading slash).
   * The matcher has to match a prefix of the remaining path.
   * If matched the value extracted by the PathMatcher is extracted on the directive level.
   */
  def rawPathPrefix(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.rawPathPrefix(segment) { inner.get().toScala }
  }
  def rawPathPrefix(pm: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.rawPathPrefix(pm.toScala) { inner.get().toScala }
  }
  def rawPathPrefix[T1](pm: PathMatcher1[T1], inner: Function[T1, Route]): Route = ScalaRoute {
    D.rawPathPrefix(pm.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def rawPathPrefix[T1, T2](pm: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.rawPathPrefix(pm.toScala) { case (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * Checks whether the unmatchedPath of the [[RequestContext]] has a prefix matched by the
   * given PathMatcher. In analogy to the `pathPrefix` directive a leading slash is implied.
   */
  def pathPrefixTest(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathPrefixTest(segment) { inner.get().toScala }
  }
  def pathPrefixTest(pm: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathPrefixTest(pm.toScala) { inner.get().toScala }
  }
  def pathPrefixTest[T1](pm: PathMatcher1[T1], inner: Function[T1, Route]): Route = ScalaRoute {
    D.pathPrefixTest(pm.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def pathPrefixTest[T1, T2](pm: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.pathPrefixTest(pm.toScala) { case (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * Checks whether the unmatchedPath of the [[RequestContext]] has a prefix matched by the
   * given PathMatcher. However, as opposed to the `pathPrefix` directive the matched path is not
   * actually "consumed".
   */
  def rawPathPrefixTest(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.rawPathPrefixTest(segment) { inner.get().toScala }
  }
  def rawPathPrefixTest(pm: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.rawPathPrefixTest(pm.toScala) { inner.get().toScala }
  }
  def rawPathPrefixTest[T1](pm: PathMatcher1[T1], inner: Function[T1, Route]): Route = ScalaRoute {
    D.rawPathPrefixTest(pm.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def rawPathPrefixTest[T1, T2](pm: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.rawPathPrefixTest(pm.toScala) { case (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * Applies the given [[PathMatcher]] to a suffix of the remaining unmatchedPath of the [[RequestContext]].
   * If matched the value extracted by the [[PathMatcher]] is extracted and the matched parts of the path are consumed.
   * Note that, for efficiency reasons, the given [[PathMatcher]] must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffix("baz" / "bar")` would match `/foo/bar/baz`!
   */
  def pathSuffix(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathSuffix(segment) { inner.get().toScala }
  }
  def pathSuffix(pm: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathSuffix(pm.toScala) { inner.get().toScala }
  }
  def pathSuffix[T1](pm: PathMatcher1[T1], inner: Function[T1, Route]): Route = ScalaRoute {
    D.pathSuffix(pm.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def pathSuffix[T1, T2](pm: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.pathSuffix(pm.toScala) { case (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * Checks whether the unmatchedPath of the [[RequestContext]] has a suffix matched by the
   * given PathMatcher. However, as opposed to the pathSuffix directive the matched path is not
   * actually "consumed".
   * Note that, for efficiency reasons, the given PathMatcher must match the desired suffix in reversed-segment
   * order, i.e. `pathSuffixTest("baz" / "bar")` would match `/foo/bar/baz`!
   */
  def pathSuffixTest(segment: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathSuffixTest(segment) { inner.get().toScala }
  }
  def pathSuffixTest(pm: PathMatcher0, inner: Supplier[Route]): Route = ScalaRoute {
    D.pathSuffixTest(pm.toScala) { inner.get().toScala }
  }
  def pathSuffixTest[T1](pm: PathMatcher1[T1], inner: Function[T1, Route]): Route = ScalaRoute {
    D.pathSuffixTest(pm.toScala) { t1 ⇒ inner.apply(t1).toScala }
  }
  def pathSuffixTest[T1, T2](pm: PathMatcher2[T1, T2], inner: BiFunction[T1, T2, Route]): Route = ScalaRoute {
    D.pathSuffixTest(pm.toScala) { case (t1, t2) ⇒ inner.apply(t1, t2).toScala }
  }

  /**
   * If the request path doesn't end with a slash, redirect to the same uri with trailing slash in the path.
   *
   * '''Caveat''': [[#path]] without trailing slash and [[#pathEnd]] directives will not match inside of this directive.
   *
   * @param redirectionType A status code from StatusCodes, which must be a redirection type.
   */
  def redirectToTrailingSlashIfMissing(redirectionType: StatusCode, inner: Supplier[Route]): Route = ScalaRoute {
    redirectionType match {
      case r: Redirection ⇒ D.redirectToTrailingSlashIfMissing(r) { inner.get().toScala }
      case _              ⇒ throw new IllegalArgumentException("Not a valid redirection status code: " + redirectionType)
    }
  }

  /**
   * If the request path ends with a slash, redirect to the same uri without trailing slash in the path.
   *
   * '''Caveat''': [[#pathSingleSlash]] directive will not match inside of this directive.
   *
   * @param redirectionType A status code from StatusCodes, which must be a redirection type.
   */
  def redirectToNoTrailingSlashIfPresent(redirectionType: StatusCode, inner: Supplier[Route]): Route = ScalaRoute {
    redirectionType match {
      case r: Redirection ⇒ D.redirectToNoTrailingSlashIfPresent(r) { inner.get().toScala }
      case _              ⇒ throw new IllegalArgumentException("Not a valid redirection status code: " + redirectionType)
    }
  }

  //------ extra java-specific methods

  // Java-specific since there's no Java API to create custom PathMatchers. And that's because there's no Path model in Java.
  /**
   * Consumes a leading slash and extracts the next path segment, unmarshalling it and passing the result to the inner function.
   */
  def pathPrefix[T](t: Unmarshaller[String, T], inner: java.util.function.Function[T, Route]): Route = ScalaRoute {
    D.pathPrefix(PathMatchers.Segment)(unmarshal(t, inner))
  }

  /**
   * Consumes a leading slash and extracts the next path segment, unmarshalling it and passing the result to the inner function,
   * expecting the full path to have been consumed then.
   */
  def path[T](t: Unmarshaller[String, T], inner: java.util.function.Function[T, Route]): Route = ScalaRoute {
    D.path(PathMatchers.Segment)(unmarshal(t, inner))
  }

  private def unmarshal[T](t: Unmarshaller[String, T], inner: java.util.function.Function[T, Route]) = { element: String ⇒
    D.extractRequestContext { ctx ⇒
      import ctx.executionContext
      import ctx.materializer

      D.onComplete(t.asScala.apply(element)) {
        case Success(value) ⇒
          inner.apply(value).toScala
        case Failure(x: IllegalArgumentException) ⇒
          D.reject()
        case Failure(x) ⇒
          D.failWith(x)
      }
    }
  }
}

