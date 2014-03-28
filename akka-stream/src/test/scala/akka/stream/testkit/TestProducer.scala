/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import asyncrx.api.Producer
import akka.stream.impl.IteratorProducer
import scala.concurrent.ExecutionContext
import asyncrx.spi.Subscriber
import asyncrx.spi.Publisher
import asyncrx.api.Consumer

object TestProducer {
  def apply[T](iterable: Iterable[T])(implicit executor: ExecutionContext): Producer[T] = apply(iterable.iterator)
  def apply[T](iterator: Iterator[T])(implicit executor: ExecutionContext): Producer[T] = new IteratorProducer[T](iterator)
  def empty[T]: Producer[T] = EmptyProducer.asInstanceOf[Producer[T]]
}

object EmptyProducer extends Producer[Nothing] with Publisher[Nothing] {
  def getPublisher: Publisher[Nothing] = this

  def subscribe(subscriber: Subscriber[Nothing]): Unit =
    subscriber.onComplete()

  def produceTo(consumer: Consumer[Nothing]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)

}