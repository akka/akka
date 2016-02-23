/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import java.io.File
import akka.http.javadsl.model.ws.Message
import akka.http.javadsl.server.values.{ PathMatcher, HttpBasicAuthenticator, OAuth2Authenticator }
import akka.stream.javadsl.Flow

import scala.language.existentials
import scala.collection.immutable
import akka.http.javadsl.model._
import akka.http.javadsl.model.headers.{ HttpCookie, EntityTag }
import akka.http.javadsl.server.directives.ContentTypeResolver
import akka.http.javadsl.server._

/**
 * INTERNAL API
 */
private[http] object RouteStructure {
  trait DirectiveRoute extends Route {
    def innerRoute: Route
    def moreInnerRoutes: immutable.Seq[Route]

    def children: immutable.Seq[Route] = innerRoute +: moreInnerRoutes

    require(children.nonEmpty)
  }
  case class RouteAlternatives()(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class MethodFilter(method: HttpMethod)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute {
    def filter(ctx: RequestContext): Boolean = ctx.request.method == method
  }

  abstract case class FileAndResourceRouteWithDefaultResolver(routeConstructor: ContentTypeResolver ⇒ Route) extends Route
  case class GetFromResource(resourcePath: String, contentType: ContentType, classLoader: ClassLoader) extends Route
  case class GetFromResourceDirectory(resourceDirectory: String, classLoader: ClassLoader, resolver: ContentTypeResolver) extends Route
  case class GetFromFile(file: File, contentType: ContentType) extends Route
  case class GetFromDirectory(directory: File, browseable: Boolean, resolver: ContentTypeResolver) extends Route
  case class Redirect(uri: Uri, redirectionType: StatusCode) extends Route {
    require(redirectionType.isRedirection, s"`redirectionType` must be a redirection status code but was $redirectionType")
  }

  case class PathSuffix(pathElements: immutable.Seq[PathMatcher[_]])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class PathSuffixTest(pathElements: immutable.Seq[PathMatcher[_]])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class RawPathPrefix(pathElements: immutable.Seq[PathMatcher[_]])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class RawPathPrefixTest(pathElements: immutable.Seq[PathMatcher[_]])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class RedirectToTrailingSlashIfMissing(redirectionType: StatusCode)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute {
    require(redirectionType.isRedirection, s"`redirectionType` must be a redirection status code but was $redirectionType")
  }
  case class RedirectToNoTrailingSlashIfPresent(redirectionType: StatusCode)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute {
    require(redirectionType.isRedirection, s"`redirectionType` must be a redirection status code but was $redirectionType")
  }
  case class Extract(extractions: Seq[StandaloneExtractionImpl[_]])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class BasicAuthentication(authenticator: HttpBasicAuthenticator[_])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class OAuth2Authentication(authenticator: OAuth2Authenticator[_])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class EncodeResponse(coders: immutable.Seq[Coder])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class DecodeRequest(coders: immutable.Seq[Coder])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute

  case class Conditional(entityTag: Option[EntityTag] = None, lastModified: Option[DateTime] = None)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute {
    require(entityTag.isDefined || lastModified.isDefined)
  }

  abstract class DynamicDirectiveRoute1[T1](val value1: RequestVal[T1])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends Route {
    def createDirective(t1: T1): Directive
  }
  abstract class DynamicDirectiveRoute2[T1, T2](val value1: RequestVal[T1], val value2: RequestVal[T2])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends Route {
    def createDirective(t1: T1, t2: T2): Directive
  }
  case class Validated(isValid: Boolean, errorMsg: String)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute

  case class HandleExceptions(handler: ExceptionHandler)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class HandleRejections(handler: RejectionHandler)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute

  sealed abstract class HostFilter extends DirectiveRoute {
    def filter(hostName: String): Boolean
  }
  case class HostNameFilter(hostWhiteList: immutable.Seq[String])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends HostFilter {
    def filter(hostName: String): Boolean = hostWhiteList.contains(hostName)
  }
  abstract class GenericHostFilter(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends HostFilter
  case class SchemeFilter(scheme: String)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute

  case class RangeSupport()(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute

  case class HandleWebSocketMessages(handler: Flow[Message, Message, Any]) extends Route

  case class SetCookie(cookie: HttpCookie)(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute
  case class DeleteCookie(name: String, domain: Option[String], path: Option[String])(val innerRoute: Route, val moreInnerRoutes: immutable.Seq[Route]) extends DirectiveRoute

  abstract class OpaqueRoute(extractions: RequestVal[_]*) extends Route {
    def handle(ctx: RequestContext): RouteResult
  }
}

