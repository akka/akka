/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package javadsl
package marshalling
package sse

import akka.NotUsed
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.model.sse.ServerSentEvent
import akka.stream.javadsl.Source

/**
 * Using `eventStreamMarshaller` lets a source of [[ServerSentEvent]]s be marshalled to a `HttpResponse`.
 */
object EventStreamMarshalling {

  /**
   * Lets a source of [[ServerSentEvent]]s be marshalled to a `HttpResponse`.
   */
  val toEventStream: Marshaller[Source[ServerSentEvent, NotUsed], RequestEntity] = {
    def asScala(eventStream: Source[ServerSentEvent, NotUsed]) =
      eventStream.asScala.map(_.asInstanceOf[scaladsl.model.sse.ServerSentEvent])
    Marshaller.fromScala(scaladsl.marshalling.sse.EventStreamMarshalling.toEventStream.compose(asScala))
  }
}
