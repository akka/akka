/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import org.reactivestreams.{ Subscriber, Subscription }

/**
 * INTERNAL API
 */

private[akka] class BlackholeSubscriber[T](highWatermark: Int, onComplete: Promise[Unit]) extends Subscriber[T] {

  private val lowWatermark: Int = Math.max(1, highWatermark / 2)
  private var requested = 0L
  private var subscription: Subscription = null

  override def onSubscribe(sub: Subscription): Unit = {
    ReactiveStreamsCompliance.requireNonNullSubscription(sub)
    if (subscription ne null) sub.cancel()
    else {
      subscription = sub
      requestMore()
    }
  }

  override def onError(cause: Throwable): Unit = {
    ReactiveStreamsCompliance.requireNonNullException(cause)
    onComplete.tryFailure(cause)
  }

  override def onComplete(): Unit = {
    onComplete.trySuccess(())
  }

  override def onNext(element: T): Unit = {
    ReactiveStreamsCompliance.requireNonNullElement(element)
    requested -= 1
    requestMore()
  }

  protected def requestMore(): Unit =
    if (requested < lowWatermark) {
      val amount = highWatermark - requested
      requested += amount
      subscription.request(amount)
    }
}
