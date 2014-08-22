/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing
package directives

import akka.event.Logging._
import akka.http.util.LoggingContext
import akka.http.model._
import akka.event.LoggingAdapter

trait DebuggingDirectives {
  import BasicDirectives._

  def logRequest(magnet: LoggingMagnet[HttpRequest ⇒ Unit]): Directive0 =
    mapRequest { request ⇒ magnet.f(request); request }

  def logResponse(magnet: LoggingMagnet[Any ⇒ Unit]): Directive0 =
    mapRouteResponse { response ⇒ magnet.f(response); response }

  def logRequestResponse(magnet: LoggingMagnet[HttpRequest ⇒ Any ⇒ Unit]): Directive0 =
    mapRequestContext { ctx ⇒
      val logResponse = magnet.f(ctx.request)
      ctx.withRouteResponseMapped { response ⇒ logResponse(response); response }
    }
}

object DebuggingDirectives extends DebuggingDirectives

case class LoggingMagnet[T](f: T) // # logging-magnet

object LoggingMagnet {
  implicit def forMessageFromMarker[T](marker: String)(implicit log: LoggingContext) = // # message-magnets
    forMessageFromMarkerAndLevel[T](marker -> DebugLevel)

  implicit def forMessageFromMarkerAndLevel[T](markerAndLevel: (String, LogLevel))(implicit log: LoggingContext) = // # message-magnets
    forMessageFromFullShow[T] {
      val (marker, level) = markerAndLevel
      Message ⇒ LogEntry(Message, marker, level)
    }

  implicit def forMessageFromShow[T](show: T ⇒ String)(implicit log: LoggingContext) = // # message-magnets
    forMessageFromFullShow[T](msg ⇒ LogEntry(show(msg), DebugLevel))

  implicit def forMessageFromFullShow[T](show: T ⇒ LogEntry)(implicit log: LoggingContext): LoggingMagnet[T ⇒ Unit] = // # message-magnets
    LoggingMagnet(show(_).logTo(log))

  implicit def forRequestResponseFromMarker(marker: String)(implicit log: LoggingContext) = // # request-response-magnets
    forRequestResponseFromMarkerAndLevel(marker -> DebugLevel)

  implicit def forRequestResponseFromMarkerAndLevel(markerAndLevel: (String, LogLevel))(implicit log: LoggingContext) = // # request-response-magnets
    forRequestResponseFromFullShow {
      val (marker, level) = markerAndLevel
      request ⇒ response ⇒ Some(
        LogEntry("Response for\n  Request : " + request + "\n  Response: " + response, marker, level))
    }

  implicit def forRequestResponseFromFullShow(show: HttpRequest ⇒ Any ⇒ Option[LogEntry])(implicit log: LoggingContext): LoggingMagnet[HttpRequest ⇒ Any ⇒ Unit] = // # request-response-magnets
    LoggingMagnet { request ⇒
      val showResponse = show(request)
      response ⇒ showResponse(response).foreach(_.logTo(log))
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
