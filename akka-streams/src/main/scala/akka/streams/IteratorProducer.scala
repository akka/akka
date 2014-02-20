package akka.streams

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

import rx.async.spi.Subscriber

/**
 * An efficient producer for iterators.
 *
 * CAUTION: This is a convenience wrapper designed for iterators over static collections.
 * Do *NOT* use it for iterators on lazy collections or other implementations that do more
 * than merely retrieve an element in their `next()` method!
 */
class IteratorProducer[T](iterator: Iterator[T], maxBufferSize: Int = 16)
  extends AbstractProducer[T](initialBufferSize = 10, maxBufferSize = maxBufferSize) {

  // compile-time constants
  private final val UNLOCKED = 0
  private final val LOCKED = 1

  private[this] val lock = new AtomicInteger(UNLOCKED) // TODO: replace with AtomicFieldUpdater / sun.misc.Unsafe

  require(maxBufferSize > 0, "maxBufferSize must be > 0")

  if (!iterator.hasNext) completeDownstream()

  // outside Publisher interface, can potentially called from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def subscribe(subscriber: Subscriber[T]): Unit =
    if (lock.compareAndSet(UNLOCKED, LOCKED)) {
      try super.subscribe(subscriber)
      finally lock.set(UNLOCKED)
    } else subscribe(subscriber)

  // called from `Subscription::requestMore`, i.e. from another thread
  // so we need to add synchronisation here
  @tailrec final override protected def moreRequested(subscription: Subscription, elements: Int): Unit =
    if (lock.compareAndSet(UNLOCKED, LOCKED)) {
      try super.moreRequested(subscription, elements)
      finally lock.set(UNLOCKED)
    } else moreRequested(subscription, elements)

  @tailrec final protected def requestFromUpstream(elements: Int): Unit =
    if (elements > 0) {
      if (iterator.hasNext) {
        pushToDownstream(iterator.next())
        requestFromUpstream(elements - 1)
      } else completeDownstream()
    }

  // called from a Subscription, i.e. probably from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def unregisterSubscription(subscription: Subscription) =
    if (lock.compareAndSet(UNLOCKED, LOCKED)) {
      try super.unregisterSubscription(subscription)
      finally lock.set(UNLOCKED)
    } else unregisterSubscription(subscription)
}
