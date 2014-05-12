/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import org.reactivestreams.spi.Publisher
import org.reactivestreams.spi.Subscriber

/**
 * INTERNAL API
 */
private[akka] case object EmptyProducer extends Producer[Nothing] with Publisher[Nothing] {
  def getPublisher: Publisher[Nothing] = this

  def subscribe(subscriber: Subscriber[Nothing]): Unit =
    subscriber.onComplete()

  def produceTo(consumer: Consumer[Nothing]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)

}

/**
 * INTERNAL API
 */
private[akka] case class ErrorProducer(t: Throwable) extends Producer[Nothing] with Publisher[Nothing] {
  def getPublisher: Publisher[Nothing] = this

  def subscribe(subscriber: Subscriber[Nothing]): Unit =
    subscriber.onError(t)

  def produceTo(consumer: Consumer[Nothing]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)

}
