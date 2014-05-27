/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.Subscription
import org.reactivestreams.spi.Subscriber

/**
 * INTERNAL API
 */

private[akka] class BlackholeConsumer[T](highWatermark: Int) extends Consumer[T] with Subscriber[T] {

  private val lowWatermark = Math.max(1, highWatermark / 2)
  private var requested = 0

  private var subscription: Subscription = _

  override def getSubscriber: Subscriber[T] = this

  override def onSubscribe(sub: Subscription): Unit = {
    subscription = sub
    requestMore()
  }

  override def onError(cause: Throwable): Unit = ()

  override def onComplete(): Unit = ()

  override def onNext(element: T): Unit = {
    requested -= 1
    requestMore()
  }

  private def requestMore(): Unit =
    if (requested < lowWatermark) {
      val amount = highWatermark - requested
      subscription.requestMore(amount)
      requested += amount
    }

}
