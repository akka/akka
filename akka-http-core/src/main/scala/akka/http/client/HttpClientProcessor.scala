/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import akka.http.model.{ HttpResponse, HttpRequest }
import org.reactivestreams.spi.{ Publisher, Subscriber }
import org.reactivestreams.api.{ Consumer, Processor }

/**
 * A `HttpClientProcessor` models an HTTP client as a stream processor that provides
 * responses for requests with an attached context object of a custom type,
 * which is funneled through and completely transparent to the processor itself.
 */
trait HttpClientProcessor[T] extends Processor[(HttpRequest, T), (HttpResponse, T)]

object HttpClientProcessor {
  def apply[T](requestSubscriber: Subscriber[(HttpRequest, T)],
               responsePublisher: Publisher[(HttpResponse, T)]): HttpClientProcessor[T] =
    new HttpClientProcessor[T] {
      def getSubscriber = requestSubscriber
      def getPublisher = responsePublisher
      def produceTo(consumer: Consumer[(HttpResponse, T)]): Unit = responsePublisher.subscribe(consumer.getSubscriber)
    }
}