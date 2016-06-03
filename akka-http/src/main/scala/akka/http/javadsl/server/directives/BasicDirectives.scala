/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.function.{ Function ⇒ JFunction }

import akka.http.impl.util.JavaMapping
import akka.http.javadsl.settings.ParserSettings
import akka.http.javadsl.settings.RoutingSettings
import akka.japi.Util

import scala.concurrent.ExecutionContextExecutor
import akka.http.impl.model.JavaUri
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.model.Uri
import akka.http.javadsl.server._
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.http.scaladsl
import akka.stream.Materializer
import java.util.function.Supplier
import java.util.{ List ⇒ JList }

import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.ResponseEntity
import akka.http.javadsl.model.HttpHeader
import akka.http.scaladsl.util.FastFuture._
import java.lang.{ Iterable ⇒ JIterable }
import java.util.concurrent.CompletionStage
import java.util.function.Predicate

import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.http.javadsl.server

import scala.compat.java8.FutureConverters._

abstract class BasicDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._
  import RoutingJavaMapping._

  def mapRequest(f: JFunction[HttpRequest, HttpRequest], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRequest(rq ⇒ f.apply(rq.asJava).asScala) { inner.get.delegate }
  }

  def mapRequestContext(f: JFunction[RequestContext, RequestContext], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRequestContext(rq ⇒ f.apply(RequestContext.toJava(rq)).asScala) { inner.get.delegate }
  }

  def mapRejections(f: JFunction[JList[Rejection], JList[Rejection]], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRejections(rejections ⇒ Util.immutableSeq(f.apply(Util.javaArrayList(rejections.map(_.asJava)))).map(_.asScala)) { inner.get.delegate }
  }

  def mapResponse(f: JFunction[HttpResponse, HttpResponse], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapResponse(resp ⇒ f.apply(resp.asJava).asScala) { inner.get.delegate }
  }

  def mapResponseEntity(f: JFunction[ResponseEntity, ResponseEntity], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapResponseEntity(e ⇒ f.apply(e.asJava).asScala) { inner.get.delegate }
  }

  def mapResponseHeaders(f: JFunction[JList[HttpHeader], JList[HttpHeader]], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapResponseHeaders(l ⇒ Util.immutableSeq(f.apply(Util.javaArrayList(l))).map(_.asScala)) { inner.get.delegate } // TODO try to remove map()
  }

  def mapInnerRoute(f: JFunction[Route, Route], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapInnerRoute(route ⇒ f(RouteAdapter(route)).delegate) { inner.get.delegate }
  }

  def mapRouteResult(f: JFunction[RouteResult, RouteResult], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRouteResult(route ⇒ f(route.asJava).asScala) { inner.get.delegate }
  }

  def mapRouteResultPF(f: PartialFunction[RouteResult, RouteResult], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRouteResult(route ⇒ f(route.asJava).asScala) { inner.get.delegate }
  }

  def mapRouteResultFuture(f: JFunction[CompletionStage[RouteResult], CompletionStage[RouteResult]], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRouteResultFuture(stage ⇒
      f(toJava(stage.fast.map(_.asJava)(ExecutionContexts.sameThreadExecutionContext))).toScala.fast.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext)) {
      inner.get.delegate
    }
  }

  def mapRouteResultWith(f: JFunction[RouteResult, CompletionStage[RouteResult]], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRouteResultWith(r ⇒ f(r.asJava).toScala.fast.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext)) { inner.get.delegate }
  }

  def mapRouteResultWithPF(f: PartialFunction[RouteResult, CompletionStage[RouteResult]], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapRouteResultWith(r ⇒ f(r.asJava).toScala.fast.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext)) { inner.get.delegate }
  }

  /**
   * Runs the inner route with settings mapped by the given function.
   */
  def mapSettings(f: JFunction[RoutingSettings, RoutingSettings], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapSettings(rs ⇒ f(rs.asJava).asScala) { inner.get.delegate }
  }

  /**
   * Always passes the request on to its inner route
   * (i.e. does nothing with the request or the response).
   */
  def pass(inner: Supplier[Route]): Route = RouteAdapter {
    D.pass { inner.get.delegate }
  }

  /**
   * Injects the given value into a directive.
   */
  def provide[T](t: T, inner: JFunction[T, Route]): Route = RouteAdapter {
    D.provide(t) { t ⇒ inner.apply(t).delegate }
  }

  /**
   * Adds a TransformationRejection cancelling all rejections equal to the given one
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejection(rejection: Rejection, inner: Supplier[Route]): Route = RouteAdapter {
    D.cancelRejection(rejection.asScala) { inner.get.delegate }
  }

  /**
   * Adds a TransformationRejection cancelling all rejections of one of the given classes
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejections(classes: JIterable[Class[_]], inner: Supplier[Route]): Route = RouteAdapter {
    D.cancelRejections(Util.immutableSeq(classes): _*) { inner.get.delegate }
  }

  /**
   * Adds a TransformationRejection cancelling all rejections for which the given filter function returns true
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejections(filter: Predicate[Rejection], inner: Supplier[Route]): Route = RouteAdapter {
    D.cancelRejections(r ⇒ filter.test(r)) { inner.get.delegate }
  }

  def recoverRejections(f: JFunction[JIterable[Rejection], RouteResult], inner: Supplier[Route]): Route = RouteAdapter {
    D.recoverRejections(rs ⇒ f.apply(Util.javaArrayList(rs.map(_.asJava))).asScala) { inner.get.delegate }
  }

  def recoverRejectionsWith(f: JFunction[JIterable[Rejection], CompletionStage[RouteResult]], inner: Supplier[Route]): Route = RouteAdapter {
    D.recoverRejectionsWith(rs ⇒ f.apply(Util.javaArrayList(rs.map(_.asJava))).toScala.fast.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext)) { inner.get.delegate }
  }

  /**
   * Transforms the unmatchedPath of the RequestContext using the given function.
   */
  def mapUnmatchedPath(f: JFunction[String, String], inner: Supplier[Route]): Route = RouteAdapter {
    D.mapUnmatchedPath(path ⇒ scaladsl.model.Uri.Path(f.apply(path.toString))) { inner.get.delegate }
  }

  /**
   * Extracts the yet unmatched path from the RequestContext.
   */
  def extractUnmatchedPath(inner: JFunction[String, Route]) = RouteAdapter {
    D.extractUnmatchedPath { path ⇒
      inner.apply(path.toString).delegate
    }
  }

  /**
   * Extracts the current [[HttpRequest]] instance.
   */
  def extractRequest(inner: JFunction[HttpRequest, Route]) = RouteAdapter {
    D.extractRequest { rq ⇒
      inner.apply(rq).delegate
    }
  }

  /**
   * Extracts the complete request URI.
   */
  def extractUri(inner: JFunction[Uri, Route]) = RouteAdapter {
    D.extractUri { uri ⇒
      inner.apply(JavaUri(uri)).delegate
    }
  }

  /**
   * Extracts the current http request entity.
   */
  @CorrespondsTo("extract")
  def extractEntity(inner: java.util.function.Function[RequestEntity, Route]): Route = RouteAdapter {
    D.extractRequest { rq ⇒
      inner.apply(rq.entity).delegate
    }
  }

  /**
   * Extracts the [[Materializer]] from the [[RequestContext]].
   */
  def extractMaterializer(inner: JFunction[Materializer, Route]): Route = RouteAdapter(
    D.extractMaterializer { m ⇒ inner.apply(m).delegate })

  /**
   * Extracts the [[ExecutionContextExecutor]] from the [[RequestContext]].
   */
  def extractExecutionContext(inner: JFunction[ExecutionContextExecutor, Route]): Route = RouteAdapter(
    D.extractExecutionContext { c ⇒ inner.apply(c).delegate })

  /**
   * Extracts a single value using the given function.
   */
  def extract[T](extract: JFunction[RequestContext, T], inner: JFunction[T, Route]): Route = RouteAdapter {
    D.extract(sc ⇒ extract.apply(JavaMapping.toJava(sc)(server.RoutingJavaMapping.RequestContext))) { c ⇒ inner.apply(c).delegate }
  }

  /**
   * Runs its inner route with the given alternative [[LoggingAdapter]].
   */
  def withLog(log: LoggingAdapter, inner: Supplier[Route]): Route = RouteAdapter {
    D.withLog(log) { inner.get.delegate }
  }

  /**
   * Runs its inner route with the given alternative [[scala.concurrent.ExecutionContextExecutor]].
   */
  def withExecutionContext(ec: ExecutionContextExecutor, inner: Supplier[Route]): Route = RouteAdapter {
    D.withExecutionContext(ec) { inner.get.delegate }
  }

  /**
   * Runs its inner route with the given alternative [[akka.stream.Materializer]].
   */
  def withMaterializer(mat: Materializer, inner: Supplier[Route]): Route = RouteAdapter {
    D.withMaterializer(mat) { inner.get.delegate }
  }

  /**
   * Runs its inner route with the given alternative [[RoutingSettings]].
   */
  def withSettings(s: RoutingSettings, inner: Supplier[Route]): Route = RouteAdapter {
    D.withSettings(s.asScala) { inner.get.delegate }
  }

  /**
   * Extracts the [[LoggingAdapter]]
   */
  def extractLog(inner: JFunction[LoggingAdapter, Route]): Route = RouteAdapter {
    D.extractLog { log ⇒ inner.apply(log).delegate }
  }

  /**
   * Extracts the [[akka.http.javadsl.settings.ParserSettings]] from the [[akka.http.javadsl.server.RequestContext]].
   */
  def extractParserSettings(inner: JFunction[ParserSettings, Route]) = RouteAdapter {
    D.extractParserSettings { settings ⇒
      inner.apply(settings).delegate
    }
  }

  /**
   * Extracts the [[RoutingSettings]] from the [[akka.http.javadsl.server.RequestContext]].
   */
  def extractSettings(inner: JFunction[RoutingSettings, Route]) = RouteAdapter {
    D.extractSettings { settings ⇒
      inner.apply(settings).delegate
    }
  }

  /**
   * Extracts the [[akka.http.javadsl.server.RequestContext]] itself.
   */
  def extractRequestContext(inner: JFunction[RequestContext, Route]) = RouteAdapter {
    D.extractRequestContext { ctx ⇒ inner.apply(JavaMapping.toJava(ctx)(server.RoutingJavaMapping.RequestContext)).delegate }
  }

}
