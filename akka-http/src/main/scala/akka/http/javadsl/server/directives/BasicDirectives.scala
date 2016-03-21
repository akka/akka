/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.function.{ Function ⇒ JFunction }
import akka.http.javadsl.settings.ParserSettings
import akka.http.scaladsl.server.directives.BasicDirectives
import akka.http.scaladsl.settings.RoutingSettings

import scala.concurrent.ExecutionContextExecutor
import akka.http.impl.model.JavaUri
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.model.Uri
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D, _ }
import akka.http.scaladsl
import akka.stream.Materializer
import java.util.function.Supplier
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import java.util.{ List ⇒ JList }
import scala.collection.JavaConverters._
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.ResponseEntity
import akka.http.javadsl.model.HttpHeader
import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.Predicate
import akka.event.LoggingAdapter

abstract class BasicDirectives {
  def mapRequest(f: JFunction[HttpRequest, HttpRequest], inner: Supplier[Route]): Route = ScalaRoute {
    D.mapRequest(rq ⇒ f.apply(rq)) { inner.get.toScala }
  }

  def mapRejections(f: JFunction[JList[Rejection], JList[Rejection]], inner: Supplier[Route]): Route = ScalaRoute {
    D.mapRejections(rejections ⇒ f.apply(rejections.asJava).asScala.toVector) { inner.get.toScala }
  }

  def mapResponse(f: JFunction[HttpResponse, HttpResponse], inner: Supplier[Route]): Route = ScalaRoute {
    D.mapResponse(resp ⇒ f.apply(resp)) { inner.get.toScala }
  }

  def mapResponseEntity(f: JFunction[ResponseEntity, ResponseEntity], inner: Supplier[Route]): Route = ScalaRoute {
    implicit val j2s = javaToScalaResponseEntity
    D.mapResponseEntity(e ⇒ f.apply(e)) { inner.get.toScala }
  }

  def mapResponseHeaders(f: JFunction[JList[HttpHeader], JList[HttpHeader]], inner: Supplier[Route]): Route = ScalaRoute {
    D.mapResponseHeaders(l ⇒ f.apply((l: Seq[HttpHeader]).asJava).asScala.toVector) { inner.get.toScala }
  }

  /**
   * Adds a TransformationRejection cancelling all rejections equal to the given one
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejection(rejection: Rejection, inner: Supplier[Route]): Route = ScalaRoute {
    D.cancelRejection(rejection) { inner.get.toScala }
  }

  /**
   * Adds a TransformationRejection cancelling all rejections of one of the given classes
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejections(classes: JIterable[Class[_]], inner: Supplier[Route]): Route = ScalaRoute {
    D.cancelRejections(classes.asScala.toSeq: _*) { inner.get.toScala }
  }

  /**
   * Adds a TransformationRejection cancelling all rejections for which the given filter function returns true
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejections(filter: Predicate[Rejection], inner: Supplier[Route]): Route = ScalaRoute {
    D.cancelRejections(r ⇒ filter.test(r)) { inner.get.toScala }
  }

  /**
   * Transforms the unmatchedPath of the RequestContext using the given function.
   */
  def mapUnmatchedPath(f: JFunction[String, String], inner: Supplier[Route]): Route = ScalaRoute {
    D.mapUnmatchedPath(path ⇒ scaladsl.model.Uri.Path(f.apply(path.toString))) { inner.get.toScala }
  }

  /**
   * Extracts the yet unmatched path from the RequestContext.
   */
  def extractUnmatchedPath(inner: JFunction[String, Route]) = ScalaRoute {
    D.extractUnmatchedPath { path ⇒
      inner.apply(path.toString).toScala
    }
  }

  /**
   * Extracts the current [[HttpRequest]] instance.
   */
  def extractRequest(inner: JFunction[HttpRequest, Route]) = ScalaRoute {
    D.extractRequest { rq ⇒
      inner.apply(rq).toScala
    }
  }

  /**
   * Extracts the complete request URI.
   */
  def extractUri(inner: JFunction[Uri, Route]) = ScalaRoute {
    D.extractUri { uri ⇒
      inner.apply(JavaUri(uri)).toScala
    }
  }

  /**
   * Extracts the current http request entity.
   */
  def extractEntity(inner: java.util.function.Function[RequestEntity, Route]): Route = ScalaRoute {
    D.extractRequest { rq ⇒
      inner.apply(rq.entity).toScala
    }
  }

  /**
   * Extracts the [[Materializer]] from the [[RequestContext]].
   */
  def extractMaterializer(inner: JFunction[Materializer, Route]): Route = ScalaRoute(
    D.extractMaterializer { m ⇒ inner.apply(m).toScala })

  /**
   * Extracts the [[ExecutionContextExecutor]] from the [[RequestContext]].
   */
  def extractExecutionContext(inner: JFunction[ExecutionContextExecutor, Route]): Route = ScalaRoute(
    D.extractExecutionContext { c ⇒ inner.apply(c).toScala })

  /**
   * Extracts a single value using the given function.
   */
  def extract[T](extract: JFunction[RequestContext, T], inner: JFunction[T, Route]): Route = ScalaRoute {
    D.extract(extract.apply) { c ⇒ inner.apply(c).toScala }
  }

  /**
   * Runs its inner route with the given alternative [[LoggingAdapter]].
   */
  def withLog(log: LoggingAdapter, inner: Supplier[Route]): Route = ScalaRoute {
    D.withLog(log) { inner.get.toScala }
  }

  /**
   * Extracts the [[LoggingAdapter]]
   */
  def extractLog(inner: JFunction[LoggingAdapter, Route]): Route = ScalaRoute {
    D.extractLog { log ⇒ inner.apply(log).toScala }
  }

  /**
   * Extracts the [[akka.http.javadsl.settings.ParserSettings]] from the [[akka.http.javadsl.server.RequestContext]].
   */
  def extractParserSettings(inner: JFunction[ParserSettings, Route]) = ScalaRoute {
    D.extractParserSettings { settings ⇒
      inner.apply(settings).toScala
    }
  }

  /**
   * Extracts the [[RoutingSettings]] from the [[akka.http.javadsl.server.RequestContext]].
   */
  def extractSettings(inner: JFunction[RoutingSettings, Route]) = ScalaRoute {
    D.extractSettings { settings ⇒
      inner.apply(settings).toScala
    }
  }
}
