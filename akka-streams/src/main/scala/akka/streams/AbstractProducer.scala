package akka.streams

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import rx.async.api
import rx.async.spi.{ Subscriber, Publisher }
import ResizableMultiReaderRingBuffer.NothingToReadException

object Producer {
  def apply[T](iterable: Iterable[T])(implicit executor: ExecutionContext): api.Producer[T] = apply(iterable.iterator)
  def apply[T](iterator: Iterator[T])(implicit executor: ExecutionContext): api.Producer[T] = new IteratorProducer[T](iterator)
  def empty[T]: api.Producer[T] = EmptyProducer.asInstanceOf[api.Producer[T]]
}

object EmptyProducer extends api.Producer[Nothing] with Publisher[Nothing] {
  def getPublisher: Publisher[Nothing] = this

  def subscribe(subscriber: Subscriber[Nothing]): Unit =
    subscriber.onComplete()
}

/**
 * Implements basic subscriber management as well as efficient "slowest-subscriber-rate" downstream fan-out support
 * with configurable and adaptive output buffer size.
 */
abstract class AbstractProducer[T](initialBufferSize: Int, maxBufferSize: Int)
  extends api.Producer[T] with Publisher[T] with ResizableMultiReaderRingBuffer.Cursors {
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
  private[this] val buffer = new ResizableMultiReaderRingBuffer[T](initialBufferSize, maxBufferSize, this)

  // optimize for small numbers of subscribers by keeping subscribers in a plain list
  private[this] var subscriptions: List[Subscription] = Nil

  // number of elements already requested but not yet received from upstream
  private[this] var pendingFromUpstream: Long = 0

  // if non-null, holds the end-of-stream state
  private[this] var endOfStream: EndOfStream = _

  // called when we are ready to consume more elements from our upstream
  // the implementation of this method is allowed to synchronously call `pushToDownstream`
  // if (part of) the requested elements are already available
  protected def requestFromUpstream(elements: Int): Unit

  // called before `shutdown()` if the stream is *not* being regularly completed
  // but shut-down due to the last subscriber having cancelled its subscription
  protected def cancelUpstream(): Unit

  // called when the Publisher/Processor is ready to be shut down.
  protected def shutdown(): Unit

  def getPublisher: Publisher[T] = this
  def cursors = subscriptions

  // called from anywhere, i.e. potentially from another thread,
  // override to add synchronization with itself, `moreRequested` and `unregisterSubscription`
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
  // override to add synchronization with itself, `subscribe` and `unregisterSubscription`
  protected def moreRequested(subscription: Subscription, elements: Int): Unit =
    if (subscription.isActive) {
      val eos = endOfStream

      // returns Int.MinValue if the subscription is to be terminated
      @tailrec def dispatchFromBufferAndReturnRemainingRequested(requested: Long): Long =
        if (requested == 0) {
          // if we are at end-of-stream and have nothing more to read we complete now rather than after the next `requestMore`
          if ((eos ne null) && buffer.count(subscription) == 0) Long.MinValue else 0
        } else {
          val x =
            try {
              val element = buffer.read(subscription)
              subscription.dispatch(element) // as long as we can produce elements from our buffer we do so synchronously
              -1 // we can't directly tailrec from here since we are in a try/catch, so signal with special value
            } catch {
              case NothingToReadException ⇒ if (eos ne null) Long.MinValue else requested // terminate or request from upstream
            }
          if (x == -1) dispatchFromBufferAndReturnRemainingRequested(requested - 1) else x
        }

      eos match {
        case null | Completed ⇒
          dispatchFromBufferAndReturnRemainingRequested(subscription.requested + elements) match {
            case Long.MinValue ⇒
              eos(subscription.subscriber)
              unregisterSubscriptionInternal(subscription)
            case 0 ⇒
              subscription.requested = 0
              requestFromUpstreamIfRequired()
            case requested ⇒
              subscription.requested = requested
              requestFromUpstreamIfPossible(capAtIntMaxValue(requested))
          }
        case ErrorCompleted(_) ⇒ // ignore, the Subscriber might not have seen our error event yet
      }
    }

  private def requestFromUpstreamIfRequired(): Unit = {
    @tailrec def maxRequested(remaining: List[Subscription], result: Long = 0): Long =
      remaining match {
        case head :: tail ⇒ maxRequested(tail, math.max(head.requested, result))
        case _            ⇒ result
      }
    if (pendingFromUpstream == 0)
      requestFromUpstreamIfPossible(capAtIntMaxValue(maxRequested(subscriptions)))
  }

  private def requestFromUpstreamIfPossible(elements: Int): Unit = {
    val toBeRequested = buffer.potentiallyAvailable(elements)
    if (toBeRequested > 0) {
      pendingFromUpstream += toBeRequested
      requestFromUpstream(toBeRequested)
    }
  }

  private def capAtIntMaxValue(long: Long): Int = math.min(long, Int.MaxValue).toInt

  // this method must be called by the implementing class whenever a new value is available to be pushed downstream
  protected def pushToDownstream(value: T): Unit = {
    @tailrec def dispatchAndReturnMaxRequested(remaining: List[Subscription], result: Long = 0): Long =
      remaining match {
        case head :: tail ⇒
          var requested = head.requested
          if (requested > 0)
            try {
              val element = buffer.read(head)
              head.dispatch(element)
              requested -= 1
              head.requested = requested
            } catch {
              case NothingToReadException ⇒ throw new IllegalStateException("Output buffer read failure") // we just wrote a value, why can't we read it?
            }
          dispatchAndReturnMaxRequested(tail, math.max(result, requested))
        case _ ⇒ result
      }

    if (endOfStream eq null) {
      pendingFromUpstream -= 1
      val writtenOk = buffer.write(value)
      if (!writtenOk) throw new IllegalStateException("Output buffer overflow")
      val maxRequested = dispatchAndReturnMaxRequested(subscriptions)
      if (pendingFromUpstream == 0) requestFromUpstreamIfPossible(capAtIntMaxValue(maxRequested))
    } else // don't throw if we have transitioned into shutdown in the mean-time, since there is an expected
    if (endOfStream ne ShutDown) // race condition between `cancelUpstream` and `pushToDownstream`
      throw new IllegalStateException("pushToDownStream(...) after completeDownstream() or abortDownstream(...)")
  }

  // this method must be called by the implementing class whenever
  // it has been determined that no more elements will be produced
  protected def completeDownstream(): Unit = {
    if (endOfStream eq null) {
      endOfStream = Completed
      // we complete all subscriptions that have no more buffered elements
      // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
      @tailrec def completeDoneSubscriptions(remaining: List[Subscription], result: List[Subscription] = Nil): List[Subscription] =
        remaining match {
          case head :: tail ⇒
            val newResult =
              if (buffer.count(head) == 0) {
                head.deactivate()
                Completed(head.subscriber)
                result
              } else head :: result
            completeDoneSubscriptions(tail, newResult)
          case _ ⇒ result
        }
      subscriptions = completeDoneSubscriptions(subscriptions)
      if (subscriptions.isEmpty) shutdown()
    } // else ignore, we need to be idempotent
  }

  // this method must be called by the implementing class to push an error downstream
  protected def abortDownstream(cause: Throwable): Unit = {
    endOfStream = ErrorCompleted(cause)
    subscriptions.foreach(s ⇒ endOfStream(s.subscriber))
    subscriptions = Nil
  }

  // called from `Subscription::cancel`, i.e. from another thread,
  // override to add synchronization with itself, `subscribe` and `moreRequested`
  protected def unregisterSubscription(subscription: Subscription): Unit =
    unregisterSubscriptionInternal(subscription)

  // must be idempotent
  private def unregisterSubscriptionInternal(subscription: Subscription): Unit = {
    // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
    def removeFrom(remaining: List[Subscription]): List[Subscription] =
      remaining match {
        case head :: tail ⇒ if (head eq subscription) tail else head :: removeFrom(tail)
        case _            ⇒ throw new IllegalStateException("Subscription to unregister not found")
      }
    if (subscription.isActive) {
      subscriptions = removeFrom(subscriptions)
      buffer.onCursorRemoved(subscription)
      subscription.deactivate()
      if (subscriptions.isEmpty) {
        if (endOfStream eq null) {
          endOfStream = ShutDown
          cancelUpstream()
        }
        shutdown()
      } else requestFromUpstreamIfRequired() // we might have removed a "blocking" subscriber and can continue now
    } // else ignore, we need to be idempotent
  }

  protected class Subscription(val subscriber: Subscriber[T])
    extends rx.async.spi.Subscription with ResizableMultiReaderRingBuffer.Cursor {

    def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("Argument must be > 0")
      else moreRequested(this, elements) // needs to be able to ignore calls after termination / cancellation

    def cancel(): Unit = unregisterSubscription(this) // must be idempotent

    /////////////// internal interface, no unsynced access from subscriber's thread //////////////

    var requested: Long = 0 // number of requested but not yet dispatched elements
    var cursor: Int = 0 // buffer cursor, set to Int.MinValue if this subscription has been cancelled / terminated

    def isActive: Boolean = cursor != Int.MinValue
    def deactivate(): Unit = cursor = Int.MinValue

    def dispatch(element: T): Unit = subscriber.onNext(element)

    override def toString: String = "Subscription" + System.identityHashCode(this) // helpful for testing
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
  private val ShutDown = new ErrorCompleted(new IllegalStateException("Cannot subscribe to shut-down Publisher"))
}