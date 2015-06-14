/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.{ Subscriber, Publisher, Subscription }

/**
 * INTERNAL API
 */
private[akka] case object EmptyPublisher extends Publisher[Nothing] {
  import ReactiveStreamsCompliance._
  override def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit =
    try {
      requireNonNullSubscriber(subscriber)
      tryOnSubscribe(subscriber, CancelledSubscription)
      tryOnComplete(subscriber)
    } catch {
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
    try {
      requireNonNullSubscriber(subscriber)
      tryOnSubscribe(subscriber, CancelledSubscription)
      tryOnError(subscriber, t)
    } catch {
      case _: SpecViolation ⇒ // nothing we can do
    }
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
  override def toString: String = name
}

private[akka] final case class SingleElementPublisher[T](value: T, name: String) extends Publisher[T] {
  import ReactiveStreamsCompliance._

  private[this] class SingleElementSubscription(subscriber: Subscriber[_ >: T]) extends Subscription {
    private[this] var done: Boolean = false
    override def cancel(): Unit = done = true

    override def request(elements: Long): Unit = if (!done) {
      if (elements < 1) rejectDueToNonPositiveDemand(subscriber)
      done = true
      try {
        tryOnNext(subscriber, value)
        tryOnComplete(subscriber)
      } catch {
        case _: SpecViolation ⇒ // TODO log?
      }
    }
  }

  override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
    try {
      requireNonNullSubscriber(subscriber)
      tryOnSubscribe(subscriber, new SingleElementSubscription(subscriber))
    } catch {
      case _: SpecViolation ⇒ // nothing we can do
    }
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
  override def toString: String = name
}

/**
 * INTERNAL API
 * This is only a legal subscription when it is immediately followed by
 * a termination signal (onComplete, onError).
 */
private[akka] case object CancelledSubscription extends Subscription {
  override def request(elements: Long): Unit = ()
  override def cancel(): Unit = ()
}

private[akka] final class CancellingSubscriber[T] extends Subscriber[T] {
  override def onError(t: Throwable): Unit = ()
  override def onSubscribe(s: Subscription): Unit = s.cancel()
  override def onComplete(): Unit = ()
  override def onNext(t: T): Unit = ()
}

/**
 * INTERNAL API
 */
private[akka] case object RejectAdditionalSubscribers extends Publisher[Nothing] {
  import ReactiveStreamsCompliance._
  override def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit =
    try {
      ReactiveStreamsCompliance.rejectAdditionalSubscriber(subscriber, "Publisher")
    } catch {
      case _: SpecViolation ⇒ // nothing we can do
    }
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
  override def toString: String = "already-subscribed-publisher"
}

