package akka.streams

import scala.annotation.tailrec
import rx.async.api.{ Producer ⇒ Prod }
import rx.async.spi.{ Subscriber, Publisher }
import java.util.concurrent.atomic.AtomicInteger

object Producer {
  def apply[T](iterable: Iterable[T]): Prod[T] = apply(iterable.iterator)
  def apply[T](iterator: Iterator[T]): Prod[T] = new IteratorProducer[T](iterator)
}

/**
 * Implements basic subscriber management as well as efficient "slowest-subscriber-rate" downstream fan-out support
 * with configurable and adaptive output buffer size.
 */
abstract class AbstractProducer[T](initialBufferSize: Int, maxBufferSize: Int)
  extends Prod[T] with Publisher[T] with ResizableMultiReaderRingBuffer.Cursors {
  import AbstractProducer._

  // we keep an element (ring) buffer which serves two purposes:
  // 1. Pre-fetch elements from our own upstream before they are requested by our downstream.
  // 2. Allow for limited request rate differences between several downstream subscribers.
  //
  // The buffering logic is as follows:
  // 1. We always request as many elements from our upstream as are still free in our current buffer instance.
  //    If a subscriber requests more than this number (either once or as a result of several `requestMore` calls)
  //    we resize the buffer if possible.
  // 2. If two subscribers drift apart in their request rates we use the buffer for keeping the elements that the
  //    slow subscriber is behind with, thereby resizing the buffer if necessary and still allowed.
  private[this] val buffer = new ResizableMultiReaderRingBuffer[AnyRef](initialBufferSize, maxBufferSize, this)

  // optimize for small numbers of subscribers by keeping subscribers in a plain list
  private[this] var subscriptions: List[Subscription] = Nil

  // number of elements already requested but not yet received from upstream
  private[this] var pendingFromUpstream: Int = 0

  // if non-null, holds the end-of-stream state
  private[this] var endOfStream: EndOfStream = _

  def getPublisher: Publisher[T] = this
  def cursors = subscriptions

  // called from anywhere, i.e. potentially from another thread,
  // override to add synchronization with itself, `moreRequested`,
  // `completeDownstream`, `abortDownstream` and `unregisterSubscription`
  def subscribe(subscriber: Subscriber[T]): Unit = {
    def doSubscribe(): Unit = {
      @tailrec def allDifferentFromNewSubscriber(remaining: List[Subscription]): Boolean =
        remaining match {
          case head :: tail ⇒ if (head.subscriber ne subscriber) allDifferentFromNewSubscriber(tail) else false
          case _            ⇒ true
        }
      val subs = subscriptions
      if (allDifferentFromNewSubscriber(subs)) {
        val newSubscription = new Subscription(subscriber)
        subscriptions = newSubscription :: subs
        buffer.initCursor(newSubscription)
        subscriber.onSubscribe(newSubscription)
      } else subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    }

    if (endOfStream eq null) doSubscribe()
    else endOfStream(subscriber)
  }

  // called from `Subscription::requestMore`, i.e. from another thread,
  // override to add synchronization with itself, `subscribe`,
  // `completeDownstream`, `abortDownstream` and `unregisterSubscription`
  protected def moreRequested(subscription: Subscription, elements: Int): Unit = {
    val eos = endOfStream

    @tailrec def dispatchFromBufferBeforeRequestingFromUpstream(requested: Int): Unit =
      if (requested > 0) {
        buffer.read(subscription) match {
          case null ⇒ // ok, we produced everything we have in the cache, no we might want to request from upstream
            if (eos eq null) {
              subscription.requested = requested
              requestFromUpstreamIfNonePending(requested)
            } else {
              subscription.terminate(eos)
              unregisterSubscriptionInternal(subscription) // idempotent
            }
          case element ⇒ // as long as we can produce elements from our buffer we do so synchronously
            subscription.subscriber.onNext(element.asInstanceOf[T])
            dispatchFromBufferBeforeRequestingFromUpstream(requested - 1)
        }
      } else if ((eos ne null) && buffer.count(subscription) == 0) {
        // if we are at end-of-stream we complete right away, rather than waiting for the next `requestMore`
        subscription.terminate(eos)
        unregisterSubscriptionInternal(subscription) // idempotent
      } else subscription.requested = 0

    eos match {
      case null | Completed  ⇒ dispatchFromBufferBeforeRequestingFromUpstream(subscription.requested + elements)
      case ErrorCompleted(_) ⇒ // ignore, the Subscriber might not have seen our error event yet
    }
  }

  private def requestFromUpstreamIfNonePending(elements: Int): Unit =
    if (pendingFromUpstream == 0) {
      val toBeRequested = buffer.potentiallyAvailable(elements)
      pendingFromUpstream = toBeRequested
      requestFromUpstream(toBeRequested)
    }

  // called when we are ready to consume more elements from our upstream
  // the implementation of this method is allowed to synchronously call `pushToDownstream` if
  // the (part of) the requested elements are already available
  protected def requestFromUpstream(elements: Int): Unit

  // this method must be called by the implementing class whenever a new value
  // is available to be pushed downstream
  // if it is not exclusively called directly by `requestFromUpstream` it must be synchronized with
  // `subscribe`, `moreRequested`, `completeDownstream`, `abortDownstream` and `unregisterSubscription`
  protected def pushToDownstream(value: T): Unit = {
    @tailrec def dispatchAndReturnMaxRequested(remaining: List[Subscription], result: Int = 0): Int =
      remaining match {
        case head :: tail ⇒
          val requestedAfterDispatch = head.requested - 1
          val maxRequested =
            if (requestedAfterDispatch >= 0) {
              buffer.read(head) match {
                case null    ⇒ throw new IllegalStateException("Output buffer read failure") // we just wrote a value, why can't we read it?
                case element ⇒ head.subscriber.onNext(element.asInstanceOf[T])
              }
              head.requested = requestedAfterDispatch
              math.max(result, requestedAfterDispatch)
            } else result
          dispatchAndReturnMaxRequested(tail, maxRequested)
        case _ ⇒ result
      }

    if (endOfStream eq null) {
      pendingFromUpstream -= 1
      val writtenOk = buffer.write(value.asInstanceOf[AnyRef])
      if (!writtenOk) throw new IllegalStateException("Output buffer overflow")
      val maxRequested = dispatchAndReturnMaxRequested(subscriptions)
      if (maxRequested > 0) requestFromUpstreamIfNonePending(maxRequested)
    } else throw new IllegalStateException("pushToDownStream(...) after completeDownstream() or abortDownstream(...)")
  }

  // this method must be called by the implementing class whenever
  // it has been determined that no more elements will be produced
  // if it is not exclusively called directly by `requestFromUpstream` it must be synchronized with
  // `subscribe`, `moreRequested`, `completeDownstream`, `abortDownstream` and `unregisterSubscription`
  protected def completeDownstream(): Unit =
    if (endOfStream eq null) {
      endOfStream = Completed
      // we complete all subscriptions that have no more buffered elements
      // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
      @tailrec def completeDoneSubscriptions(remaining: List[Subscription], result: List[Subscription] = Nil): List[Subscription] =
        remaining match {
          case head :: tail ⇒
            val newResult =
              if (buffer.count(head) == 0) {
                head.terminate(Completed)
                result
              } else head :: result
            completeDoneSubscriptions(tail, newResult)
          case _ ⇒ result
        }
      subscriptions = completeDoneSubscriptions(subscriptions)
    } else throw new IllegalStateException("Cannot completeDownstream() twice")

  // this method must be called by the implementing class to push an error downstream
  // if it is not exclusively called directly by `requestFromUpstream` it must be synchronized with itself,
  // `subscribe`, `moreRequested`, `completeDownstream` and `unregisterSubscription`
  protected def abortDownstream(cause: Throwable): Unit = {
    val eos = ErrorCompleted(cause)
    endOfStream = eos
    subscriptions.foreach(_.terminate(eos))
  }

  // called from `Subscription::cancel`, i.e. from another thread,
  // override to add synchronization with itself, `subscribe`, `moreRequested`,
  // `completeDownstream` and `abortDownstream`,
  protected def unregisterSubscription(subscription: Subscription): Unit =
    unregisterSubscriptionInternal(subscription)

  private def unregisterSubscriptionInternal(subscription: Subscription): Unit = {
    // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
    def removeFrom(remaining: List[Subscription]): List[Subscription] =
      remaining match {
        case head :: tail ⇒ if (head eq subscription) tail else head :: removeFrom(tail)
        case _            ⇒ Nil // we need to be idempotent
      }
    subscriptions = removeFrom(subscriptions)
  }

  protected class Subscription(val subscriber: Subscriber[T])
    extends AtomicInteger with rx.async.spi.Subscription with ResizableMultiReaderRingBuffer.Cursor {
    private final val ACTIVE = 0
    private final val TERMINATED = 1
    private final val CANCELLED = 2

    var requested: Int = 0 // number of requested but not yet dispatched elements
    var cursor: Int = 0

    def requestMore(elements: Int): Unit =
      get match {
        case ACTIVE ⇒
          if (elements <= 0) throw new IllegalArgumentException("Argument must be > 0")
          moreRequested(this, elements) // NOTE: `moreRequested` still needs to be able to handle inactive subscriptions!
        case TERMINATED ⇒ // ignore
        case CANCELLED  ⇒ throw new IllegalStateException("Cannot requestMore from a cancelled subscription")
      }

    def cancel(): Unit =
      if (compareAndSet(ACTIVE, CANCELLED))
        unregisterSubscription(this)
      else if (!compareAndSet(TERMINATED, CANCELLED))
        throw new IllegalStateException("Cannot cancel an already cancelled subscription")

    def terminate(eos: EndOfStream): Unit =
      if (compareAndSet(ACTIVE, TERMINATED)) // don't change from CANCELLED to TERMINATED
        eos(subscriber)
  }
}

object AbstractProducer {
  private sealed trait EndOfStream {
    def apply[T](subscriber: Subscriber[T]): Unit
  }
  private object Completed extends EndOfStream {
    def apply[T](subscriber: Subscriber[T]): Unit = subscriber.onComplete()
  }
  private case class ErrorCompleted(cause: Throwable) extends EndOfStream {
    def apply[T](subscriber: Subscriber[T]): Unit = subscriber.onError(cause)
  }
}