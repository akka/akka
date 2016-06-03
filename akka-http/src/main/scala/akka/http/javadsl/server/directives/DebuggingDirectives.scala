/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.{ BiFunction, Supplier, Function ⇒ JFunction }
import java.util.{ Optional, List ⇒ JList }

import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.http.javadsl.model.{ HttpRequest, HttpResponse }
import akka.http.javadsl.server.{ Route, RoutingJavaMapping }
import akka.http.scaladsl
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.http.scaladsl.server.{ Rejection, RouteResult, Directives ⇒ D }

import scala.collection.JavaConverters._

abstract class DebuggingDirectives extends CookieDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._
  import RoutingJavaMapping._

  /**
   * Produces a log entry for every incoming request.
   */
  def logRequest(marker: String, inner: Supplier[Route]): Route = RouteAdapter {
    D.logRequest(marker) { inner.get.delegate }
  }

  /**
   * Produces a log entry for every incoming request.
   *
   * @param level One of the log levels defined in akka.event.Logging
   */
  def logRequest(marker: String, level: LogLevel, inner: Supplier[Route]): Route = RouteAdapter {
    D.logRequest(marker) { inner.get.delegate }
  }

  /**
   * Produces a log entry for every incoming request.
   */
  def logRequest(show: JFunction[HttpRequest, LogEntry], inner: Supplier[Route]): Route = RouteAdapter {
    D.logRequest(LoggingMagnet.forMessageFromFullShow(rq ⇒ show.apply(rq).asScala)) { inner.get.delegate }
  }

  /**
   * Produces a log entry for every route result.
   */
  def logResult(marker: String, inner: Supplier[Route]): Route = RouteAdapter {
    D.logResult(marker) { inner.get.delegate }
  }

  /**
   * Produces a log entry for every route result.
   *
   * @param level One of the log levels defined in akka.event.Logging
   */
  def logResult(marker: String, level: LogLevel, inner: Supplier[Route]): Route = RouteAdapter {
    D.logResult(marker) { inner.get.delegate }
  }

  /**
   * Produces a log entry for every route result.
   *
   * @param showSuccess Function invoked when the route result was successful and yielded an HTTP response
   * @param showRejection Function invoked when the route yielded a rejection
   */
  def logResult(
    showSuccess:   JFunction[HttpResponse, LogEntry],
    showRejection: JFunction[JList[Rejection], LogEntry],
    inner:         Supplier[Route]) = RouteAdapter {
    D.logResult(LoggingMagnet.forMessageFromFullShow {
      case RouteResult.Complete(response)   ⇒ showSuccess.apply(response).asScala
      case RouteResult.Rejected(rejections) ⇒ showRejection.apply(rejections.asJava).asScala
    }) {
      inner.get.delegate
    }
  }

  /**
   * Produces a log entry for every request/response combination.
   *
   * @param showSuccess Function invoked when the route result was successful and yielded an HTTP response
   * @param showRejection Function invoked when the route yielded a rejection
   */
  def logRequestResult(
    showSuccess:   BiFunction[HttpRequest, HttpResponse, LogEntry],
    showRejection: BiFunction[HttpRequest, JList[Rejection], LogEntry],
    inner:         Supplier[Route]) = RouteAdapter {
    D.logRequestResult(LoggingMagnet.forRequestResponseFromFullShow(request ⇒ {
      case RouteResult.Complete(response)   ⇒ Some(showSuccess.apply(request, response).asScala)
      case RouteResult.Rejected(rejections) ⇒ Some(showRejection.apply(request, rejections.asJava).asScala)
    })) {
      inner.get.delegate
    }
  }

  /**
   * Optionally produces a log entry for every request/response combination.
   *
   * @param showSuccess Function invoked when the route result was successful and yielded an HTTP response
   * @param showRejection Function invoked when the route yielded a rejection
   */
  @CorrespondsTo("logRequestResult")
  def logRequestResultOptional(
    showSuccess:   BiFunction[HttpRequest, HttpResponse, Optional[LogEntry]],
    showRejection: BiFunction[HttpRequest, JList[Rejection], Optional[LogEntry]],
    inner:         Supplier[Route]) = RouteAdapter {
    D.logRequestResult(LoggingMagnet.forRequestResponseFromFullShow(request ⇒ {
      case RouteResult.Complete(response)   ⇒ showSuccess.apply(request, response).asScala
      case RouteResult.Rejected(rejections) ⇒ showRejection.apply(request, rejections.asJava).asScala
    })) {
      inner.get.delegate
    }
  }
}

abstract class LogEntry {
  def getObj: Any
  def getLevel: LogLevel
}

object LogEntry {
  def create(obj: Any, level: LogLevel): LogEntry = scaladsl.server.directives.LogEntry(obj, level)
  def debug(obj: Any): LogEntry = scaladsl.server.directives.LogEntry(obj, Logging.DebugLevel)
  def info(obj: Any): LogEntry = scaladsl.server.directives.LogEntry(obj, Logging.InfoLevel)
  def warning(obj: Any): LogEntry = scaladsl.server.directives.LogEntry(obj, Logging.WarningLevel)
  def error(obj: Any): LogEntry = scaladsl.server.directives.LogEntry(obj, Logging.ErrorLevel)
}