package akka.streams

import java.util.concurrent.atomic.AtomicBoolean
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
  extends AbstractProducer[T](initialBufferSize = 1, maxBufferSize = maxBufferSize) {

  if (!iterator.hasNext) completeDownstream()

  @tailrec final protected def requestFromUpstream(elements: Int): Unit =
    if (elements > 0) {
      if (iterator.hasNext) {
        pushToDownstream(iterator.next())
        requestFromUpstream(elements - 1)
      } else completeDownstream()
    } else if (!iterator.hasNext) completeDownstream() // complete eagerly
}

/**
 * Base class for producers that can provide their elements synchronously.
 */
abstract class AbstractStrictProducer[T](initialBufferSize: Int, maxBufferSize: Int)
  extends AbstractProducer[T](initialBufferSize, maxBufferSize) {

  private[this] val locked = new AtomicBoolean // TODO: replace with AtomicFieldUpdater / sun.misc.Unsafe

  // outside Publisher interface, can potentially called from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def subscribe(subscriber: Subscriber[T]): Unit =
    if (locked.compareAndSet(false, true)) {
      try super.subscribe(subscriber)
      finally locked.set(false)
    } else subscribe(subscriber)

  // called from `Subscription::requestMore`, i.e. from another thread
  // so we need to add synchronisation here
  @tailrec final override protected def moreRequested(subscription: Subscription, elements: Int): Unit =
    if (locked.compareAndSet(false, true)) {
      try super.moreRequested(subscription, elements)
      finally locked.set(false)
    } else moreRequested(subscription, elements)

  // called from a Subscription, i.e. probably from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def unregisterSubscription(subscription: Subscription) =
    if (locked.compareAndSet(false, true)) {
      try super.unregisterSubscription(subscription)
      finally locked.set(false)
    } else unregisterSubscription(subscription)
}