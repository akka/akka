/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.Done

import scala.concurrent.Promise
import org.reactivestreams.{ Subscriber, Subscription }

/**
 * INTERNAL API
 */

private[akka] final class SinkholeSubscriber[T](whenComplete: Promise[Done]) extends Subscriber[T] {
  private[this] var running: Boolean = false

  override def onSubscribe(sub: Subscription): Unit = {
    ReactiveStreamsCompliance.requireNonNullSubscription(sub)
    if (running) sub.cancel()
    else {
      running = true
      sub.request(Long.MaxValue)
    }
  }

  override def onError(cause: Throwable): Unit = {
    ReactiveStreamsCompliance.requireNonNullException(cause)
    whenComplete.tryFailure(cause)
  }

  override def onComplete(): Unit = whenComplete.trySuccess(Done)

  override def onNext(element: T): Unit = ReactiveStreamsCompliance.requireNonNullElement(element)
}
