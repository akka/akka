/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package marshalling
package sse

import akka.annotation.ApiMayChange
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.scaladsl.Source

/**
 * Importing [[EventStreamMarshalling.toEventStream]] lets a source of [[ServerSentEvent]]s be marshalled to a
 * `HttpEntity` and hence as a `HttpResponse`.
 */
@ApiMayChange
object EventStreamMarshalling extends EventStreamMarshalling

/**
 * Mixing in this trait lets a source of [[ServerSentEvent]]s be marshalled to a `HttpEntity` and hence as a
 * `HttpResponse`.
 */
@ApiMayChange
trait EventStreamMarshalling {

  implicit final val toEventStream: ToEntityMarshaller[Source[ServerSentEvent, Any]] = {
    def marshal(messages: Source[ServerSentEvent, Any]) = HttpEntity(`text/event-stream`, messages.map(_.encode))
    Marshaller.withFixedContentType(`text/event-stream`)(marshal)
  }
}
