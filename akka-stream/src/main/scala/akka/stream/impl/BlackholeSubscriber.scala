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

  private val lowWatermark = Math.max(1, highWatermark / 2)
  private var requested = 0L

  private val subscription: AtomicReference[Subscription] = new AtomicReference(null)

  override def onSubscribe(sub: Subscription): Unit = {
    ReactiveStreamsCompliance.requireNonNullSubscription(sub)
    if (subscription.compareAndSet(null, sub)) requestMore()
    else sub.cancel()
  }

  override def onError(cause: Throwable): Unit = {
    ReactiveStreamsCompliance.requireNonNullException(cause)
    onComplete.tryFailure(cause)
    ()
  }

  override def onComplete(): Unit = onComplete.trySuccess(())

  override def onNext(element: T): Unit = {
    ReactiveStreamsCompliance.requireNonNullElement(element)
    requested -= 1
    requestMore()
  }

  protected def requestMore(): Unit =
    if (requested < lowWatermark) {
      val amount = highWatermark - requested
      subscription.get().request(amount)
      requested += amount
    }

}
