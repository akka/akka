package akka.streams

import rx.async.api.{ Producer ⇒ Prod }
import rx.async.spi.{ Subscriber, Publisher }
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

object Producer {
  def apply[T](iterable: Iterable[T]): Prod[T] = apply(iterable.iterator)
  def apply[T](iterator: Iterator[T]): Prod[T] = new IteratorProducer[T](iterator)

  sealed trait State
  object State {
    case object Active extends State
    case object Completed extends State
    case class Error(cause: Throwable) extends State
  }
}

/**
 * An efficient producer for iterators.
 */
class IteratorProducer[T](iterator: Iterator[T]) extends AbstractProducer[T] {
  private final val UNLOCKED = 0
  private final val LOCKED = 1

  private[this] val lock = new AtomicInteger(UNLOCKED) // TODO: replace with AtomicFieldUpdater / sun.misc.Unsafe

  startWith(if (iterator.hasNext) Producer.State.Active else Producer.State.Completed)

  protected def requestFromUpstream(elements: Int): Unit =
    if (elements > 0) {
      if (iterator.hasNext) {
        pushToDownstream(iterator.next())
        requestFromUpstream(elements - 1)
      } else completeDownstream()
    }

  protected def handleOverflow(value: T): Unit =
    // we shouldn't see this as all pushing-to-downstream is synchronous to the `requestMore` calls from the downstream
    throw new IllegalStateException

  // outside Publisher interface, can potentially called from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def subscribe(subscriber: Subscriber[T]): Unit =
    if (lock.compareAndSet(UNLOCKED, LOCKED)) {
      try super.subscribe(subscriber)
      finally lock.set(UNLOCKED)
    } else subscribe(subscriber)

  // called from a Subscription, i.e. probably from another thread,
  // so we need to wrap with synchronization
  @tailrec final override protected def requestFromUpstreamIfRequired(): Unit =
    if (lock.compareAndSet(UNLOCKED, LOCKED)) {
      try super.requestFromUpstreamIfRequired()
      finally lock.set(UNLOCKED)
    } else requestFromUpstreamIfRequired()

  // called from a Subscription, i.e. probably from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def unregisterSubscription(subscription: Subscription) =
    if (lock.compareAndSet(UNLOCKED, LOCKED)) {
      try super.unregisterSubscription(subscription)
      finally lock.set(UNLOCKED)
    } else unregisterSubscription(subscription)
}

/**
 * Implements basic subscriber management as well as efficient "slowest-subscriber-rate" downstream fan-out support.
 */
abstract class AbstractProducer[T] extends Prod[T] with Publisher[T] {
  import Producer.State

  // optimize for small numbers of subscribers by keeping subscribers in a plain list
  // this var must be initialized by the implementing class via a call to `startWith`
  @volatile private[this] var stateBehavior: StateBehavior = _

  // must be called in the constructor of the implementing class
  protected def startWith(state: State): Unit =
    stateBehavior = state match {
      case State.Active       ⇒ new ActiveStateBehavior
      case State.Completed    ⇒ new CompletedStateBehavior
      case State.Error(cause) ⇒ new ErrorStateBehavior(cause)
    }

  def getPublisher: Publisher[T] = this

  def state: State = stateBehavior.state

  def subscribe(subscriber: Subscriber[T]): Unit =
    stateBehavior.subscribe(subscriber)

  // called when our subscribers are currently ready to receive the given number of additional elements
  protected def requestFromUpstream(elements: Int): Unit

  // somehow handle incoming elements when we are not allowed to push them downstream (yet)
  protected def handleOverflow(value: T): Unit

  protected def pushToDownstream(value: T): Unit =
    stateBehavior.pushToDownstream(value)

  protected def completeDownstream(): Unit =
    stateBehavior.completeDownstream()

  protected def requestFromUpstreamIfRequired(): Unit =
    stateBehavior.requestFromUpstreamIfRequired()

  protected def unregisterSubscription(subscription: Subscription): Unit =
    stateBehavior.unregisterSubscription(subscription)

  trait StateBehavior {
    def state: State
    def subscribe(subscriber: Subscriber[T]): Unit
    def pushToDownstream(value: T): Unit
    def completeDownstream(): Unit
    def unregisterSubscription(subscription: Subscription): Unit
    def requestFromUpstreamIfRequired(): Unit
  }

  class ActiveStateBehavior extends StateBehavior {
    @volatile private[this] var subscriptions: List[Subscription] = Nil
    @volatile private[this] var pendingFromUpstream: Int = 0 // number of elements already requested and not yet received from upstream

    def state: State = State.Active

    def subscribe(subscriber: Subscriber[T]): Unit = {
      @tailrec def allDifferentFromNewSubscriber(remaining: List[Subscription]): Boolean =
        remaining match {
          case head :: tail ⇒ if (head.subscriber ne subscriber) allDifferentFromNewSubscriber(tail) else false
          case _            ⇒ true
        }
      val subs = subscriptions
      if (allDifferentFromNewSubscriber(subs)) {
        val subscription = new Subscription(subscriber)
        subscriptions = subscription :: subs
        subscriber.onSubscribe(subscription)
      } else subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    }

    def pushToDownstream(value: T): Unit = {
      @tailrec def allCanReceive(remaining: List[Subscription]): Boolean =
        remaining match {
          case head :: tail ⇒ head.get() > 0 && allCanReceive(tail)
          case _            ⇒ true
        }
      @tailrec def dispatchTo(remaining: List[Subscription]): Unit =
        remaining match {
          case head :: tail ⇒
            head.dispatch(value); dispatchTo(tail)
          case _ ⇒
        }

      pendingFromUpstream -= 1
      // if we have a subscriber which cannot take this element we must not dispatch it to ANY subscriber,
      // also, if we have no subscriber (any more), we cannot dispatch and therefore forward to overflow handling
      val subs = subscriptions
      if (subs.nonEmpty && allCanReceive(subs))
        dispatchTo(subs)
      else
        handleOverflow(value)
    }

    def completeDownstream(): Unit = {
      @tailrec def complete(remaining: List[Subscription]): Unit =
        remaining match {
          case head :: tail ⇒
            head.complete()
            complete(tail)
          case _ ⇒
        }
      complete(subscriptions)
      stateBehavior = new CompletedStateBehavior
    }

    def requestFromUpstreamIfRequired(): Unit = {
      val subs = subscriptions
      if (subs.nonEmpty) {
        @tailrec def findMinRequested(remaining: List[Subscription], result: Int = Int.MaxValue): Int =
          remaining match {
            case head :: tail ⇒ findMinRequested(tail, math.min(result, head.get()))
            case _            ⇒ result
          }
        val minReq = findMinRequested(subs)
        val pfu = pendingFromUpstream
        if (minReq > pfu) {
          pendingFromUpstream = minReq
          requestFromUpstream(minReq - pfu)
        }
      }
    }

    def unregisterSubscription(subscription: Subscription): Unit = {
      // is non-tail recursion acceptable here? (it's the fastest impl but might stack overflow for large numbers of subscribers)
      def removeFrom(remaining: List[Subscription]): List[Subscription] =
        remaining match {
          case head :: tail ⇒ if (head eq subscription) tail else head :: removeFrom(tail)
          case _            ⇒ throw new IllegalStateException("Cannot find Subscription to unregister")
        }
      subscriptions = removeFrom(subscriptions)
    }
  }

  abstract class FinalStateBehavior extends StateBehavior {
    def pushToDownstream(value: T): Unit = handleOverflow(value)
    def requestFromUpstreamIfRequired(): Unit = () // we must ignore, since the Subscriber might not have seen our state change yet
    def unregisterSubscription(subscription: Subscription): Unit = () // we must ignore
  }

  class CompletedStateBehavior extends FinalStateBehavior {
    def state: State = State.Completed
    def subscribe(subscriber: Subscriber[T]): Unit = subscriber.onComplete()
    def completeDownstream(): Unit = throw new IllegalStateException("Cannot completeDownstream() twice")
  }

  class ErrorStateBehavior(cause: Throwable) extends FinalStateBehavior {
    def state: State = State.Error(cause)
    def subscribe(subscriber: Subscriber[T]): Unit = subscriber.onError(cause)
    def completeDownstream(): Unit = throw new IllegalStateException("Cannot completeDownstream() in the error state")
  }

  protected class Subscription(val subscriber: Subscriber[T]) extends AtomicInteger with rx.async.spi.Subscription {
    @volatile var cancelled = false

    def requestMore(elements: Int): Unit =
      if (cancelled) throw new IllegalStateException("Cannot request from a cancelled subscription")
      else if (elements <= 0) throw new IllegalArgumentException("Argument must be > 0")
      else {
        addAndGet(elements)
        requestFromUpstreamIfRequired()
      }

    def cancel(): Unit =
      if (cancelled) throw new IllegalStateException("Cannot cancel an already cancelled subscription")
      else {
        cancelled = true
        unregisterSubscription(this)
      }

    def dispatch(value: T): Unit = {
      decrementAndGet()
      subscriber.onNext(value)
    }

    def complete(): Unit = {
      cancelled = true
      subscriber.onComplete()
    }
  }
}