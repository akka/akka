/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.{ Subscriber, Publisher }

/**
 * INTERNAL API
 */
private[akka] case object EmptyPublisher extends Publisher[Nothing] {
  def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit = subscriber.onComplete()
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
}

/**
 * INTERNAL API
 */
private[akka] case class ErrorPublisher(t: Throwable) extends Publisher[Nothing] {
  def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit = subscriber.onError(t)
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
}
