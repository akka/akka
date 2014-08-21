/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import org.reactivestreams.{ Subscriber, Subscription }

/**
 * INTERNAL API
 */

private[akka] class BlackholeSubscriber[T](highWatermark: Int) extends Subscriber[T] {

  private val lowWatermark = Math.max(1, highWatermark / 2)
  private var requested = 0L

  private val subscription: AtomicReference[Subscription] = new AtomicReference(null)

  override def onSubscribe(sub: Subscription): Unit = {
    if (subscription.compareAndSet(null, sub)) requestMore()
    else sub.cancel()
  }

  override def onError(cause: Throwable): Unit = ()

  override def onComplete(): Unit = ()

  override def onNext(element: T): Unit = {
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
