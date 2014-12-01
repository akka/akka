/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import java.io.File

import scala.language.existentials

import akka.http.model.japi.{ DateTime, ContentType, HttpMethod }
import akka.http.model.japi.headers.EntityTag
import akka.http.server.japi.directives.ContentTypeResolver

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[japi] object RouteStructure {
  trait DirectiveRoute extends Route {
    def children: immutable.Seq[Route]

    require(children.nonEmpty)
  }
  case class RouteAlternatives(children: immutable.Seq[Route]) extends DirectiveRoute

  case class MethodFilter(method: HttpMethod, children: immutable.Seq[Route]) extends DirectiveRoute {
    def filter(ctx: RequestContext): Boolean = ctx.request.method == method
  }

  abstract case class FileAndResourceRouteWithDefaultResolver(routeConstructor: ContentTypeResolver ⇒ Route) extends Route
  case class GetFromResource(resourcePath: String, contentType: ContentType, classLoader: ClassLoader) extends Route
  case class GetFromResourceDirectory(resourceDirectory: String, classLoader: ClassLoader, resolver: ContentTypeResolver) extends Route
  case class GetFromFile(file: File, contentType: ContentType) extends Route
  case class GetFromDirectory(directory: File, browseable: Boolean, resolver: ContentTypeResolver) extends Route

  case class RawPathPrefix(pathElements: immutable.Seq[PathMatcher[_]], children: immutable.Seq[Route]) extends DirectiveRoute
  case class Extract(extractions: Seq[StandaloneExtractionImpl[_]], children: immutable.Seq[Route]) extends DirectiveRoute
  case class BasicAuthentication(authenticator: HttpBasicAuthenticator[_], children: immutable.Seq[Route]) extends DirectiveRoute
  case class EncodeResponse(coders: immutable.Seq[Coder], children: immutable.Seq[Route]) extends DirectiveRoute

  case class Conditional(entityTag: EntityTag, lastModified: DateTime, children: immutable.Seq[Route]) extends DirectiveRoute

  abstract class OpaqueRoute(extractions: RequestVal[_]*) extends Route {
    def handle(ctx: RequestContext): RouteResult
  }
}

