/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.annotation.InternalApi
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import scala.concurrent.{ ExecutionContext, Promise }

/**
 * INTERNAL API
 */
@InternalApi private[akka] case object EmptyPublisher extends Publisher[Nothing] {
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
@InternalApi private[akka] final case class ErrorPublisher(t: Throwable, name: String) extends Publisher[Nothing] {
  ReactiveStreamsCompliance.requireNonNullElement(t)

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

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class MaybePublisher[T](
  promise: Promise[Option[T]],
  name:    String)(implicit ec: ExecutionContext) extends Publisher[T] {
  import ReactiveStreamsCompliance._

  private[this] class MaybeSubscription(subscriber: Subscriber[_ >: T]) extends Subscription {
    private[this] var done: Boolean = false
    override def cancel(): Unit = {
      done = true
      promise.trySuccess(None)
    }

    override def request(elements: Long): Unit = {
      if (elements < 1) rejectDueToNonPositiveDemand(subscriber)
      if (!done) {
        done = true
        promise.future foreach {
          // We consciously do not catch SpecViolation here, it will be reported to the ExecutionContext
          case Some(v) ⇒
            tryOnNext(subscriber, v)
            tryOnComplete(subscriber)
          case None ⇒
            tryOnComplete(subscriber)
        }
      }
    }
  }

  override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
    try {
      requireNonNullSubscriber(subscriber)
      tryOnSubscribe(subscriber, new MaybeSubscription(subscriber))
      promise.future.failed.foreach {
        error ⇒ tryOnError(subscriber, error)
      }
    } catch {
      case sv: SpecViolation ⇒ ec.reportFailure(sv)
    }

  override def toString: String = name
}

/**
 * INTERNAL API
 * This is only a legal subscription when it is immediately followed by
 * a termination signal (onComplete, onError).
 */
@InternalApi private[akka] case object CancelledSubscription extends Subscription {
  override def request(elements: Long): Unit = ()
  override def cancel(): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class CancellingSubscriber[T] extends Subscriber[T] {
  override def onError(t: Throwable): Unit = ()
  override def onSubscribe(s: Subscription): Unit = s.cancel()
  override def onComplete(): Unit = ()
  override def onNext(t: T): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] case object RejectAdditionalSubscribers extends Publisher[Nothing] {
  import ReactiveStreamsCompliance._
  override def subscribe(subscriber: Subscriber[_ >: Nothing]): Unit =
    try rejectAdditionalSubscriber(subscriber, "Publisher") catch {
      case _: SpecViolation ⇒ // nothing we can do
    }
  def apply[T]: Publisher[T] = this.asInstanceOf[Publisher[T]]
  override def toString: String = "already-subscribed-publisher"
}
