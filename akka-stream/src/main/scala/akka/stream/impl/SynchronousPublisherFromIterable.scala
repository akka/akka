/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object SynchronousPublisherFromIterable {
  def apply[T](iterable: immutable.Iterable[T]): Publisher[T] =
    if (iterable.isEmpty) EmptyPublisher.asInstanceOf[Publisher[T]]
    else new SynchronousPublisherFromIterable(iterable)

  private class IteratorSubscription[T](subscriber: Subscriber[T], iterator: Iterator[T]) extends Subscription {
    var done = false
    var demand = 0
    var pushing = false

    override def cancel(): Unit =
      done = true

    override def request(elements: Int): Unit = {
      @tailrec def pushNext(): Unit = {
        if (!done)
          if (iterator.isEmpty) {
            done = true
            subscriber.onComplete()
          } else if (demand != 0) {
            demand -= 1
            subscriber.onNext(iterator.next())
            pushNext()
          }
      }

      if (pushing)
        demand += elements // reentrant call to requestMore from onNext
      else {
        try {
          pushing = true
          demand = elements
          pushNext()
        } catch {
          case NonFatal(e) ⇒
            done = true
            subscriber.onError(e)
        } finally { pushing = false }
      }
    }
  }
}

/**
 * INTERNAL API
 * Publisher that will push all requested elements from the iterator of the iterable
 * to the subscriber in the calling thread of `requestMore`.
 *
 * It is only intended to be used with iterators over static collections.
 * Do *NOT* use it for iterators on lazy collections or other implementations that do more
 * than merely retrieve an element in their `next()` method!
 *
 * It is the responsibility of the subscriber to provide necessary memory visibility
 * if calls to `requestMore` and `cancel` are performed from different threads.
 * For example, usage from an actor is fine. Concurrent calls to the subscription is not allowed.
 * Reentrant calls to `requestMore` directly from `onNext` are supported by this publisher.
 */
private[akka] class SynchronousPublisherFromIterable[T](private val iterable: immutable.Iterable[T]) extends Publisher[T] {

  import akka.stream.impl.SynchronousPublisherFromIterable.IteratorSubscription

  override def subscribe(subscriber: Subscriber[T]): Unit =
    subscriber.onSubscribe(new IteratorSubscription(subscriber, iterable.iterator))

  override def equals(o: Any): Boolean = o match {
    case other: SynchronousPublisherFromIterable[T] ⇒ iterable == other.iterable
    case _ ⇒ false
  }

  override def hashCode: Int = iterable.hashCode

  override def toString: String = s"SynchronousPublisherFromIterable(${iterable.mkString(", ")})"
}
