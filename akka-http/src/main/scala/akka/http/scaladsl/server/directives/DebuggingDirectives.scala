/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.event.Logging._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._

/**
 * @groupname debugging Debugging directives
 * @groupprio debugging 40
 */
trait DebuggingDirectives {
  import BasicDirectives._

  /**
   * Produces a log entry for every incoming request.
   *
   * @group debugging
   */
  def logRequest(magnet: LoggingMagnet[HttpRequest ⇒ Unit]): Directive0 =
    extractRequestContext.flatMap { ctx ⇒
      magnet.f(ctx.log)(ctx.request)
      pass
    }

  /**
   * Produces a log entry for every [[RouteResult]].
   *
   * @group debugging
   */
  def logResult(magnet: LoggingMagnet[RouteResult ⇒ Unit]): Directive0 =
    extractRequestContext.flatMap { ctx ⇒
      mapRouteResult { result ⇒
        magnet.f(ctx.log)(result)
        result
      }
    }

  /**
   * Produces a log entry for every incoming request and [[RouteResult]].
   *
   * @group debugging
   */
  def logRequestResult(magnet: LoggingMagnet[HttpRequest ⇒ RouteResult ⇒ Unit]): Directive0 =
    extractRequestContext.flatMap { ctx ⇒
      val logResult = magnet.f(ctx.log)(ctx.request)
      mapRouteResult { result ⇒
        logResult(result)
        result
      }
    }
}

object DebuggingDirectives extends DebuggingDirectives

case class LoggingMagnet[T](f: LoggingAdapter ⇒ T) // # logging-magnet

object LoggingMagnet {
  implicit def forMessageFromMarker[T](marker: String): LoggingMagnet[T ⇒ Unit] = // # message-magnets
    forMessageFromMarkerAndLevel[T](marker -> DebugLevel)

  implicit def forMessageFromMarkerAndLevel[T](markerAndLevel: (String, LogLevel)): LoggingMagnet[T ⇒ Unit] = // # message-magnets
    forMessageFromFullShow[T] {
      val (marker, level) = markerAndLevel
      Message ⇒ LogEntry(Message, marker, level)
    }

  implicit def forMessageFromShow[T](show: T ⇒ String): LoggingMagnet[T ⇒ Unit] = // # message-magnets
    forMessageFromFullShow[T](msg ⇒ LogEntry(show(msg), DebugLevel))

  implicit def forMessageFromFullShow[T](show: T ⇒ LogEntry): LoggingMagnet[T ⇒ Unit] = // # message-magnets
    LoggingMagnet(log ⇒ show(_).logTo(log))

  implicit def forRequestResponseFromMarker(marker: String): LoggingMagnet[HttpRequest ⇒ RouteResult ⇒ Unit] = // # request-response-magnets
    forRequestResponseFromMarkerAndLevel(marker -> DebugLevel)

  implicit def forRequestResponseFromMarkerAndLevel(markerAndLevel: (String, LogLevel)): LoggingMagnet[HttpRequest ⇒ RouteResult ⇒ Unit] = // # request-response-magnets
    forRequestResponseFromFullShow {
      val (marker, level) = markerAndLevel
      request ⇒ response ⇒ Some(
        LogEntry("Response for\n  Request : " + request + "\n  Response: " + response, marker, level))
    }

  implicit def forRequestResponseFromFullShow(show: HttpRequest ⇒ RouteResult ⇒ Option[LogEntry]): LoggingMagnet[HttpRequest ⇒ RouteResult ⇒ Unit] = // # request-response-magnets
    LoggingMagnet { log ⇒
      request ⇒
        val showResult = show(request)
        result ⇒ showResult(result).foreach(_.logTo(log))
    }
}

case class LogEntry(obj: Any, level: LogLevel = DebugLevel) {
  def logTo(log: LoggingAdapter): Unit = {
    log.log(level, obj.toString)
  }
}

object LogEntry {
  def apply(obj: Any, marker: String, level: LogLevel): LogEntry =
    LogEntry(if (marker.isEmpty) obj else marker + ": " + obj, level)
}
