/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.NotUsed
import akka.annotation.ApiMayChange
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.scaladsl.{ Keep, Source }

/**
 * Importing [[EventStreamUnmarshalling.fromEventStream]] lets a `HttpEntity` with a `text/event-stream` media type be
 * unmarshalled to a source of [[ServerSentEvent]]s.
 *
 * The maximum size for parsing server-sent events is 8KiB. The maximum size for parsing lines of a server-sent event
 * is 4KiB. If you need to customize any of these, use the [[EventStreamUnmarshalling]] trait and override the
 * respective methods.
 */
@ApiMayChange
object EventStreamUnmarshalling extends EventStreamUnmarshalling

/**
 * Mixing in this trait lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of
 * [[ServerSentEvent]]s.
 *
 * The maximum size for parsing server-sent events is 8KiB by default and can be customized by overriding
 * [[EventStreamUnmarshalling.maxEventSize]]. The maximum size for parsing lines of a server-sent event is 4KiB dy
 * default and can be customized by overriding [[EventStreamUnmarshalling.maxLineSize]].
 */
@ApiMayChange
trait EventStreamUnmarshalling {

  /**
   * The maximum size for parsing server-sent events; 8KiB by default.
   */
  protected def maxEventSize: Int =
    8192

  /**
   * The maximum size for parsing lines of a server-sent event; 4KiB by default.
   */
  protected def maxLineSize: Int =
    4096

  implicit final val fromEventStream: FromEntityUnmarshaller[Source[ServerSentEvent, NotUsed]] = {
    val eventStreamParser = EventStreamParser(maxLineSize, maxEventSize)
    def unmarshal(entity: HttpEntity) =
      entity
        .withoutSizeLimit // Because of streaming: the server keeps the response open and potentially streams huge amounts of data
        .dataBytes
        .viaMat(eventStreamParser)(Keep.none)
    Unmarshaller.strict(unmarshal).forContentTypes(`text/event-stream`)
  }
}
