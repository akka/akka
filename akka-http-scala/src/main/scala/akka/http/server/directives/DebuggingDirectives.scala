/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.event.Logging._
import akka.event.LoggingAdapter
import akka.http.model._

trait DebuggingDirectives {
  import BasicDirectives._

  def logRequest(magnet: LoggingMagnet[HttpRequest ⇒ Unit]): Directive0 =
    extractRequestContext.flatMap { ctx ⇒
      magnet.f(ctx.log)(ctx.request)
      pass
    }

  def logResult(magnet: LoggingMagnet[RouteResult ⇒ Unit]): Directive0 =
    extractRequestContext.flatMap { ctx ⇒
      mapRouteResult { result ⇒
        magnet.f(ctx.log)(result)
        result
      }
    }

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
  implicit def forMessageFromMarker[T](marker: String) = // # message-magnets
    forMessageFromMarkerAndLevel[T](marker -> DebugLevel)

  implicit def forMessageFromMarkerAndLevel[T](markerAndLevel: (String, LogLevel)) = // # message-magnets
    forMessageFromFullShow[T] {
      val (marker, level) = markerAndLevel
      Message ⇒ LogEntry(Message, marker, level)
    }

  implicit def forMessageFromShow[T](show: T ⇒ String) = // # message-magnets
    forMessageFromFullShow[T](msg ⇒ LogEntry(show(msg), DebugLevel))

  implicit def forMessageFromFullShow[T](show: T ⇒ LogEntry): LoggingMagnet[T ⇒ Unit] = // # message-magnets
    LoggingMagnet(log ⇒ show(_).logTo(log))

  implicit def forRequestResponseFromMarker(marker: String) = // # request-response-magnets
    forRequestResponseFromMarkerAndLevel(marker -> DebugLevel)

  implicit def forRequestResponseFromMarkerAndLevel(markerAndLevel: (String, LogLevel)) = // # request-response-magnets
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
