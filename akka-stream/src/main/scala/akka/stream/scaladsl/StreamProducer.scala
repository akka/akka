/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.util.control.NonFatal
import org.reactivestreams.spi.{ Publisher, Subscription, Subscriber }
import org.reactivestreams.api.{ Consumer, Producer }

object StreamProducer {

  // a producer that always completes the subscriber directly in `subscribe`
  def empty[T]: Producer[T] = EmptyProducer.asInstanceOf[Producer[T]]

  // case object so we get value equality
  case object EmptyProducer extends AbstractProducer[Any] {
    def subscribe(subscriber: Subscriber[Any]) = subscriber.onComplete()
  }

  // a producer that always calls `subscriber.onError` directly in `subscribe`
  def error[T](error: Throwable): Producer[T] = new ErrorProducer(error).asInstanceOf[Producer[T]]

  // case class so we get value equality
  case class ErrorProducer(error: Throwable) extends AbstractProducer[Any] {
    def subscribe(subscriber: Subscriber[Any]) = subscriber.onError(error)
  }

  /**
   * Shortcut for constructing a `ForIterable`.
   */
  def of[T](elements: T*): Producer[T] = if (elements.isEmpty) empty else apply(elements)

  /**
   * Shortcut for constructing a `ForIterable`.
   */
  def apply[T](iterable: Iterable[T]): Producer[T] =
    iterable match {
      case x: Seq[_] if x.isEmpty ⇒ empty[T]
      case _                      ⇒ ForIterable(iterable)
    }

  /**
   * Shortcut for constructing an `ForIterable`.
   */
  def apply[T](option: Option[T]): Producer[T] = if (option.isEmpty) empty else ForIterable(option.get :: Nil)

  /**
   * A producer supporting unlimited subscribers which all receive independent subscriptions which
   * efficiently produce the elements of the given Iterable synchronously in `subscription.requestMore`.
   * Provides value equality.
   */
  case class ForIterable[T](iterable: Iterable[T]) extends AbstractProducer[T] {
    def subscribe(subscriber: Subscriber[T]) =
      subscriber.onSubscribe(new IteratorSubscription(iterable.iterator, subscriber))
  }

  /**
   * Constructs a Producer which efficiently produces the elements from the given iterator
   * synchronously in `subscription.requestMore`.
   *
   * CAUTION: This is a convenience wrapper designed for iterators over static collections.
   * Do *NOT* use it for iterators on lazy collections or other implementations that do more
   * than merely retrieve an element in their `next()` method!
   */
  def apply[T](iterator: Iterator[T]): Producer[T] =
    new AtomicBoolean with AbstractProducer[T] {
      def subscribe(subscriber: Subscriber[T]) =
        if (compareAndSet(false, true)) {
          subscriber.onSubscribe(new IteratorSubscription(iterator, subscriber))
        } else subscriber.onError(new RuntimeException("Cannot subscribe more than one subscriber"))
      override def toString = s"IteratorProducer($iterator)"
    }

  private class IteratorSubscription[T](iterator: Iterator[T], subscriber: Subscriber[T]) extends Subscription {
    @volatile var completed = false
    @tailrec final def requestMore(elements: Int) =
      if (!completed && elements > 0) {
        val recurse =
          try {
            if (iterator.hasNext) {
              subscriber.onNext(iterator.next())
              true
            } else {
              completed = true
              subscriber.onComplete()
              false
            }
          } catch {
            case NonFatal(e) ⇒
              subscriber.onError(e)
              false
          }
        if (recurse) requestMore(elements - 1)
      }
    def cancel() = completed = true
  }

  trait AbstractProducer[T] extends Producer[T] with Publisher[T] {
    def getPublisher: Publisher[T] = this
    def produceTo(consumer: Consumer[T]) = subscribe(consumer.getSubscriber)
  }
}

