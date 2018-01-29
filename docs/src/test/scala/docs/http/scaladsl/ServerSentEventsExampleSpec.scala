/*
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.server.{ Directives, Route, RoutingSpec }
import docs.CompileOnlySpec

final class ServerSentEventsExampleSpec extends RoutingSpec with Directives with CompileOnlySpec {

  "stream example" in compileOnlySpec {
    //#event-stream-marshalling-example
    import akka.NotUsed
    import akka.stream.scaladsl.Source

    import akka.http.scaladsl.Http
    import akka.http.scaladsl.unmarshalling.Unmarshal
    import akka.http.scaladsl.model.sse.ServerSentEvent
    import scala.concurrent.duration._

    import java.time.LocalTime
    import java.time.format.DateTimeFormatter.ISO_LOCAL_TIME

    def route: Route = {
      import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

      path("events") {
        get {
          complete {
            Source
              .tick(2.seconds, 2.seconds, NotUsed)
              .map(_ => LocalTime.now())
              .map(time => ServerSentEvent(ISO_LOCAL_TIME.format(time)))
              .keepAlive(1.second, () => ServerSentEvent.heartbeat)
          }
        }
      }
    }
    //#event-stream-marshalling-example

    //#event-stream-unmarshalling-example
    import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._

    Http()
      .singleRequest(Get("http://localhost:8000/events"))
      .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
      .foreach(_.runForeach(println))
    //#event-stream-unmarshalling-example
  }
}
