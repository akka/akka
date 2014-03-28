/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import org.reactivestreams.api
import org.reactivestreams.spi
import SubscriberManagement.ShutDown
import ResizableMultiReaderRingBuffer.NothingToReadException

/**
 * INTERNAL API
 */
private[akka] object SubscriberManagement {

  sealed trait EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit
  }

  object NotReached extends EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit = throw new IllegalStateException("Called apply on NotReached")
  }

  object Completed extends EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit = subscriber.onComplete()
  }

  case class ErrorCompleted(cause: Throwable) extends EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit = subscriber.onError(cause)
  }

  val ShutDown = new ErrorCompleted(new IllegalStateException("Cannot subscribe to shut-down spi.Publisher"))
}

/**
 * INTERNAL API
 */
private[akka] trait SubscriptionWithCursor extends spi.Subscription with ResizableMultiReaderRingBuffer.Cursor {
  def subscriber[T]: spi.Subscriber[T]
  def isActive: Boolean = cursor != Int.MinValue
  def deactivate(): Unit = cursor = Int.MinValue

  def dispatch[T](element: T): Unit = subscriber.onNext(element)

  override def toString: String = "Subscription" + System.identityHashCode(this) // helpful for testing

  /////////////// internal interface, no unsynced access from subscriber's thread //////////////

  var requested: Long = 0 // number of requested but not yet dispatched elements
  var cursor: Int = 0 // buffer cursor, set to Int.MinValue if this subscription has been cancelled / terminated
}

/**
 * INTERNAL API
 */
private[akka] trait SubscriberManagement[T] extends ResizableMultiReaderRingBuffer.Cursors {
  import SubscriberManagement._
  type S <: SubscriptionWithCursor
  type Subscriptions = List[S]

  def initialBufferSize: Int
  def maxBufferSize: Int

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
  private[this] var subscriptions: Subscriptions = Nil

  // number of elements already requested but not yet received from upstream
  private[this] var pendingFromUpstream: Long = 0

  // if non-null, holds the end-of-stream state
  private[this] var endOfStream: EndOfStream = NotReached

  // called when we are ready to consume more elements from our upstream
  // the implementation of this method is allowed to synchronously call `pushToDownstream`
  // if (part of) the requested elements are already available
  protected def requestFromUpstream(elements: Int): Unit

  // called before `shutdown()` if the stream is *not* being regularly completed
  // but shut-down due to the last subscriber having cancelled its subscription
  protected def cancelUpstream(): Unit

  // called when the spi.Publisher/Processor is ready to be shut down.
  protected def shutdown(): Unit

  // Use to register a subscriber
  protected def createSubscription(subscriber: spi.Subscriber[T]): S

  def cursors = subscriptions

  // called from `Subscription::requestMore`, i.e. from another thread,
  // override to add synchronization with itself, `subscribe` and `unregisterSubscription`
  protected def moreRequested(subscription: S, elements: Int): Unit =
    if (subscription.isActive) {

      // returns Long.MinValue if the subscription is to be terminated
      @tailrec def dispatchFromBufferAndReturnRemainingRequested(requested: Long, eos: EndOfStream): Long =
        if (requested == 0) {
          // if we are at end-of-stream and have nothing more to read we complete now rather than after the next `requestMore`
          if ((eos ne NotReached) && buffer.count(subscription) == 0) Long.MinValue else 0
        } else {
          val x =
            try {
              subscription.dispatch(buffer.read(subscription)) // as long as we can produce elements from our buffer we do so synchronously
              -1 // we can't directly tailrec from here since we are in a try/catch, so signal with special value
            } catch {
              case NothingToReadException ⇒ if (eos ne NotReached) Long.MinValue else requested // terminate or request from upstream
            }
          if (x == -1) dispatchFromBufferAndReturnRemainingRequested(requested - 1, eos) else x
        }

      endOfStream match {
        case eos @ (NotReached | Completed) ⇒
          val demand = subscription.requested + elements
          assert(demand >= 0)
          dispatchFromBufferAndReturnRemainingRequested(demand, eos) match {
            case Long.MinValue ⇒
              eos(subscription.subscriber)
              unregisterSubscriptionInternal(subscription)
            case 0 ⇒
              subscription.requested = 0
              requestFromUpstreamIfRequired()
            case requested ⇒
              subscription.requested = requested
              requestFromUpstreamIfPossible(requested)
          }
        case ErrorCompleted(_) ⇒ // ignore, the spi.Subscriber might not have seen our error event yet
      }
    }

  private[this] final def requestFromUpstreamIfRequired(): Unit = {
    @tailrec def maxRequested(remaining: Subscriptions, result: Long = 0): Long =
      remaining match {
        case head :: tail ⇒ maxRequested(tail, math.max(head.requested, result))
        case _            ⇒ result
      }
    if (pendingFromUpstream == 0)
      requestFromUpstreamIfPossible(maxRequested(subscriptions))
  }

  private[this] final def requestFromUpstreamIfPossible(elements: Long): Unit = {
    val toBeRequested = buffer.potentiallyAvailable(math.min(elements, Int.MaxValue).toInt) // Cap at Int.MaxValue
    if (toBeRequested > 0) {
      pendingFromUpstream += toBeRequested
      requestFromUpstream(toBeRequested)
    }
  }

  // this method must be called by the implementing class whenever a new value is available to be pushed downstream
  protected def pushToDownstream(value: T): Unit = {
    @tailrec def dispatchAndReturnMaxRequested(remaining: Subscriptions, result: Long = 0): Long =
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

    endOfStream match {
      case NotReached ⇒
        pendingFromUpstream -= 1
        if (!buffer.write(value)) throw new IllegalStateException("Output buffer overflow")
        val maxRequested = dispatchAndReturnMaxRequested(subscriptions)
        if (pendingFromUpstream == 0) requestFromUpstreamIfPossible(maxRequested)
      case ShutDown ⇒ // don't throw if we have transitioned into shutdown in the mean-time, since there is an expected
      case _ ⇒ // race condition between `cancelUpstream` and `pushToDownstream`
        throw new IllegalStateException("pushToDownStream(...) after completeDownstream() or abortDownstream(...)")
    }
  }

  // this method must be called by the implementing class whenever
  // it has been determined that no more elements will be produced
  protected def completeDownstream(): Unit = {
    if (endOfStream eq NotReached) {
      // we complete all subscriptions that have no more buffered elements
      // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
      @tailrec def completeDoneSubscriptions(remaining: Subscriptions, result: Subscriptions = Nil): Subscriptions =

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
      endOfStream = Completed
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

  protected def registerSubscriber(subscriber: spi.Subscriber[T]): Unit = endOfStream match {
    case NotReached if subscriptions.exists(_.subscriber eq subscriber) ⇒
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    case NotReached ⇒
      val newSubscription = createSubscription(subscriber)
      subscriptions ::= newSubscription
      buffer.initCursor(newSubscription)
      subscriber.onSubscribe(newSubscription)
    case Completed if buffer.nonEmpty ⇒
      val newSubscription = createSubscription(subscriber)
      subscriptions ::= newSubscription
      buffer.initCursor(newSubscription)
      subscriber.onSubscribe(newSubscription)
    case eos ⇒
      eos(subscriber)
  }

  // called from `Subscription::cancel`, i.e. from another thread,
  // override to add synchronization with itself, `subscribe` and `moreRequested`
  protected def unregisterSubscription(subscription: S): Unit =
    unregisterSubscriptionInternal(subscription)

  // must be idempotent
  private def unregisterSubscriptionInternal(subscription: S): Unit = {
    // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
    def removeFrom(remaining: Subscriptions): Subscriptions =
      remaining match {
        case head :: tail ⇒ if (head eq subscription) tail else head :: removeFrom(tail)
        case _            ⇒ throw new IllegalStateException("Subscription to unregister not found")
      }
    if (subscription.isActive) {
      subscriptions = removeFrom(subscriptions)
      buffer.onCursorRemoved(subscription)
      subscription.deactivate()
      if (subscriptions.isEmpty) {
        if (endOfStream eq NotReached) {
          endOfStream = ShutDown
          cancelUpstream()
        }
        shutdown()
      } else requestFromUpstreamIfRequired() // we might have removed a "blocking" subscriber and can continue now
    } // else ignore, we need to be idempotent
  }
}

/*
 * FIXME: THIS BELOW NEEDS TO BE REMOVED, IT IS NOT USED BY ActorProcessorImpl
 */

/**
 * INTERNAL API
 *
 * Implements basic subscriber management as well as efficient "slowest-subscriber-rate" downstream fan-out support
 * with configurable and adaptive output buffer size.
 */
private[akka] abstract class AbstractProducer[T](val initialBufferSize: Int, val maxBufferSize: Int)
  extends api.Producer[T] with spi.Publisher[T] with SubscriberManagement[T] {
  type S = Subscription

  def getPublisher: spi.Publisher[T] = this

  // called from anywhere, i.e. potentially from another thread,
  // override to add synchronization with itself, `moreRequested` and `unregisterSubscription`
  def subscribe(subscriber: spi.Subscriber[T]): Unit = registerSubscriber(subscriber)

  override def createSubscription(subscriber: spi.Subscriber[T]): S = new Subscription(subscriber)

  protected class Subscription(val _subscriber: spi.Subscriber[T]) extends SubscriptionWithCursor {
    def subscriber[B]: spi.Subscriber[B] = _subscriber.asInstanceOf[spi.Subscriber[B]]

    override def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("Argument must be > 0")
      else moreRequested(this, elements) // needs to be able to ignore calls after termination / cancellation

    override def cancel(): Unit = unregisterSubscription(this) // must be idempotent

  }
}

