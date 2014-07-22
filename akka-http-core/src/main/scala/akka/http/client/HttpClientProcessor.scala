/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import akka.http.model.{ HttpResponse, HttpRequest }
import org.reactivestreams.{ Subscription, Publisher, Subscriber, Processor }

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
      override def subscribe(s: Subscriber[(HttpResponse, T)]): Unit = responsePublisher.subscribe(s)

      override def onError(t: Throwable): Unit = requestSubscriber.onError(t)
      override def onSubscribe(s: Subscription): Unit = requestSubscriber.onSubscribe(s)
      override def onComplete(): Unit = requestSubscriber.onComplete()
      override def onNext(t: (HttpRequest, T)): Unit = requestSubscriber.onNext(t)
    }
}