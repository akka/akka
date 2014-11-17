/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.ReactiveStreamsConstants
import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object AsynchronousIterablePublisher {
  def apply[T](iterable: immutable.Iterable[T], name: String, executor: ExecutionContext): Publisher[T] =
    new AsynchronousIterablePublisher(iterable, name, executor)

  object IteratorSubscription {
    def apply[T](subscriber: Subscriber[T], iterator: Iterator[T], executor: ExecutionContext): Unit =
      new IteratorSubscription[T](subscriber, iterator, executor).init()
  }

  private[this] sealed trait State
  private[this] final case object Unitialized extends State
  private[this] final case object Initializing extends State
  private[this] final case object Initialized extends State
  private[this] final case object Cancelled extends State
  private[this] final case object Completed extends State
  private[this] final case object Errored extends State

  private[this] final class IteratorSubscription[T](subscriber: Subscriber[T],
                                                    iterator: Iterator[T], // TODO null out iterator when completed?
                                                    executor: ExecutionContext)
    extends AtomicLong with Subscription with Runnable {
    import ReactiveStreamsConstants._
    // FIXME if we want to get crazy, cache-line pad this class
    private[this] val scheduled = new AtomicBoolean(false)
    // FIXME if we want to get even more crazy, we could encode these states into an AtomicInteger and merge it with scheduled
    @volatile private[this] var state: State = Unitialized

    // TODO/FIXME technically we could use the fact that we're an AtomicLong to ensure visibility of this
    //Should only be called once, please
    def init(): Unit = if (state == Unitialized && scheduled.compareAndSet(false, true)) executor.execute(this)

    override def cancel(): Unit = state = Cancelled

    override def request(elements: Long): Unit = {
      ReactiveStreamsConstants.validateRequest(elements)
      if (getAndAdd(elements) == 0 && scheduled.compareAndSet(false, true)) executor.execute(this) // FIXME overflow protection
    }

    override def run(): Unit = try {
      def scheduleForExecutionIfHasDemand(): Unit =
        if (get() > 0 && scheduled.compareAndSet(false, true)) executor.execute(this) // loop via executor

      @tailrec def loop(): Unit = {
        state match {
          case current @ (Initialized | Initializing) ⇒
            // The only transition that can occur from the outside is to Cancelled
            getAndSet(0) match {
              case 0 if current eq Initialized ⇒
                scheduled.set(false)
                scheduleForExecutionIfHasDemand()
              case n ⇒

                @tailrec def push(n: Long): State =
                  state match { // Important to do the volatile read here since we are checking for external cancellation
                    case c @ Cancelled ⇒ c
                    case s if iterator.hasNext ⇒
                      if (n > 0) {
                        tryOnNext(subscriber, iterator.next())
                        push(n - 1)
                      } else s
                    case _ ⇒ Completed
                  }

                (try push(n): AnyRef catch {
                  case NonFatal(t: AnyRef) ⇒ t
                }) match {
                  case Initialized ⇒
                    loop()
                  case Unitialized ⇒
                    state = Errored
                    tryOnError(subscriber, new IllegalStateException("BUG: AsynchronousIterablePublisher was Uninitialized!"))
                  case Initializing ⇒
                    state = Initialized
                    loop()
                  case Cancelled | Errored ⇒ ()
                  case Completed ⇒
                    state = Completed
                    tryOnComplete(subscriber)
                  case s: SpecViolation ⇒
                    state = Errored
                    executor.reportFailure(s.violation)
                  case t: Throwable ⇒
                    state = Errored
                    tryOnError(subscriber, t)
                }
            }
          case Unitialized ⇒
            state = Initializing
            tryOnSubscribe(subscriber, this) // If this fails, this is a spec violation
            loop()
          case Cancelled | Completed | Errored ⇒ () // Do nothing
        }
      }

      loop()
    } catch {
      case NonFatal(e) ⇒ executor.reportFailure(e) // This should never happen. Last words.
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
private[akka] final class AsynchronousIterablePublisher[T](
  private[this] val iterable: immutable.Iterable[T],
  private[this] val name: String,
  private[this] val executor: ExecutionContext) extends Publisher[T] {

  import AsynchronousIterablePublisher.IteratorSubscription

  override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
    try IteratorSubscription(subscriber, iterable.iterator, executor) catch {
      case NonFatal(t) ⇒ ErrorPublisher(t, name).subscribe(subscriber) // FIXME this is dodgy
    }

  override def toString: String = name
}
