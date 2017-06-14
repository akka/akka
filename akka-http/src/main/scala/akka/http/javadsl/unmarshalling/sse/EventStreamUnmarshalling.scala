/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package javadsl
package unmarshalling
package sse

import akka.NotUsed
import akka.http.javadsl.model.HttpEntity
import akka.http.javadsl.model.sse.ServerSentEvent
import akka.stream.javadsl.Source

/**
 * Using `fromEventStream` lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of
 * `ServerSentEvent`s.
 */
object EventStreamUnmarshalling {

  /**
   * Lets a `HttpEntity` with a `text/event-stream` media type be unmarshalled to a source of `ServerSentEvent`s.
   */
  val fromEventStream: Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]] =
    scaladsl.unmarshalling.sse.EventStreamUnmarshalling.fromEventStream
      .map(_.map(_.asInstanceOf[ServerSentEvent]).asJava)
      .asInstanceOf[Unmarshaller[HttpEntity, Source[ServerSentEvent, NotUsed]]]
}
