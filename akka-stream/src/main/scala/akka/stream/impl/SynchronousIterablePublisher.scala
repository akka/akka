/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.dispatch.ExecutionContexts

import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.stream.impl.ReactiveStreamsCompliance._

/**
 * INTERNAL API
 */
private[akka] object SynchronousIterablePublisher {
  def apply[T](iterable: immutable.Iterable[T], name: String): Publisher[T] =
    new SynchronousIterablePublisher(iterable, name)

  object IteratorSubscription {
    def apply[T](subscriber: Subscriber[T], iterator: Iterator[T]): Unit =
      new IteratorSubscription[T](subscriber, iterator).init()
  }

  private[this] final class IteratorSubscription[T](subscriber: Subscriber[T], iterator: Iterator[T]) extends Subscription {
    var done = false
    var pendingDemand = 0L
    var pushing = false

    import ReactiveStreamsCompliance._

    def init(): Unit = try {
      if (!iterator.hasNext) { // Let's be prudent and issue onComplete immediately
        cancel()
        tryOnSubscribe(subscriber, this)
        tryOnComplete(subscriber)
      } else {
        tryOnSubscribe(subscriber, this)
      }
    } catch {
      case sv: SpecViolation ⇒
        cancel()
        throw sv // I think it is prudent to "escalate" the spec violation
      case NonFatal(e) ⇒
        cancel()
        tryOnError(subscriber, e)
    }

    override def cancel(): Unit = done = true

    override def request(elements: Long): Unit = {
      if (done) () // According to Reactive Streams Spec 3.6, `request` on a cancelled `Subscription` must be a NoOp
      else if (elements < 1) { // According to Reactive Streams Spec 3.9, with non-positive demand must yield onError
        cancel()
        rejectDueToNonPositiveDemand(subscriber)
      } else {
        pendingDemand += elements
        if (pendingDemand < 1)
          pendingDemand = Long.MaxValue // Long overflow, Reactive Streams Spec 3:17: effectively unbounded
        if (!pushing) {
          // According to Reactive Streams Spec 3:3, we must prevent unbounded recursion
          try {
            pushing = true
            pendingDemand = elements

            @tailrec def pushNext(): Unit =
              if (done) ()
              else if (iterator.isEmpty) {
                cancel()
                tryOnComplete(subscriber)
              } else if (pendingDemand > 0) {
                pendingDemand -= 1
                tryOnNext(subscriber, iterator.next())
                pushNext()
              }

            pushNext()
          } catch {
            case sv: SpecViolation ⇒
              cancel()
              throw sv // I think it is prudent to "escalate" the spec violation
            case NonFatal(e) ⇒
              cancel()
              tryOnError(subscriber, e)
          } finally {
            pushing = false
          }
        }
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
private[akka] final class SynchronousIterablePublisher[T](
  private val iterable: immutable.Iterable[T],
  private val name: String) extends Publisher[T] {

  import SynchronousIterablePublisher.IteratorSubscription

  override def subscribe(subscriber: Subscriber[_ >: T]): Unit = {
    requireNonNullSubscriber(subscriber)
    IteratorSubscription(subscriber, try iterable.iterator catch { case NonFatal(t) ⇒ Iterator.continually(throw t) })
  }

  override def toString: String = name
}
