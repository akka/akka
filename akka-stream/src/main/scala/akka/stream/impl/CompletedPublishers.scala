/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.{ Subscriber, Publisher }

/**
 * INTERNAL API
 */
private[akka] case object EmptyPublisher extends Publisher[Nothing] {
  import ReactiveStreamsCompliance._
  override def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit =
    try tryOnComplete(subscriber) catch {
      case _: SpecViolation ⇒ // nothing we can do
    }
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
  override def toString: String = "already-completed-publisher"
}

/**
 * INTERNAL API
 */
private[akka] final case class ErrorPublisher(t: Throwable, name: String) extends Publisher[Nothing] {
  import ReactiveStreamsCompliance._
  override def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit =
    try tryOnError(subscriber, t) catch {
      case _: SpecViolation ⇒ // nothing we can do
    }
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
  override def toString: String = name
}
