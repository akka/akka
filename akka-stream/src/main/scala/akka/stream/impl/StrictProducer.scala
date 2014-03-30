/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import org.reactivestreams.spi
import org.reactivestreams.api.Consumer

/**
 * INTERNAL API
 *
 * An efficient producer for iterators.
 *
 * CAUTION: This is a convenience wrapper designed for iterators over static collections.
 * Do *NOT* use it for iterators on lazy collections or other implementations that do more
 * than merely retrieve an element in their `next()` method!
 */
private[akka] class IteratorProducer[T](
  iterator: Iterator[T],
  maxBufferSize: Int = 16,
  maxRecursionLevel: Int = 32,
  maxSyncBatchSize: Int = 128)(implicit executor: ExecutionContext) //FIXME Remove defaults in code
  extends AbstractStrictProducer[T](initialBufferSize = 1, maxBufferSize, maxRecursionLevel, maxSyncBatchSize) {

  if (!iterator.hasNext) completeDownstream()

  @tailrec final protected def pushNext(count: Int): Unit =
    if (iterator.hasNext) {
      if (count > 0) {
        pushToDownstream(iterator.next())
        pushNext(count - 1)
      }
    } else completeDownstream()
}

/**
 * INTERNAL API
 *
 * Base class for producers that can provide their elements synchronously.
 *
 * For efficiency it tries to produce elements synchronously before returning from `requestMore`.
 * If the requested element count is > the given `maxSyncBatchSize` or there are still scheduled
 * "productions" pending then (part of) the requested elements are produced asynchronously via the
 * given executionContext.
 *
 * Also, in order to protect against stack overflow, the given `maxRecursionLevel` limits the number
 * of nested call iterations between the fanout logic and the synchronous production logic provided
 * by `AbstractStrictProducer`. If the `maxRecursionLevel` is surpassed the synchronous production
 * loop is stopped and production of the remaining elements scheduled to the given executor.
 */
private[akka] abstract class AbstractStrictProducer[T](
  initialBufferSize: Int,
  maxBufferSize: Int,
  maxRecursionLevel: Int = 32,
  maxSyncBatchSize: Int = 128)(implicit executor: ExecutionContext) //FIXME Remove defaults in code
  extends AbstractProducer[T](initialBufferSize, maxBufferSize) {

  private[this] val locked = new AtomicBoolean // TODO: replace with AtomicFieldUpdater / sun.misc.Unsafe
  private[this] var pending = 0L
  private[this] var recursionLevel = 0

  def produceTo(consumer: Consumer[T]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)

  /**
   * Implement with the actual production logic.
   * It should synchronously call `pushToDownstream(...)` the given number of times.
   * If less than (or equal to!) `count` elements are still available `completeDownstream()` must be called after
   * all remaining elements have been pushed.
   */
  protected def pushNext(count: Int): Unit

  protected def requestFromUpstream(elements: Int): Unit = {
    recursionLevel += 1
    try {
      if (pending == 0) {
        if (recursionLevel <= maxRecursionLevel) produce(elements)
        else schedule(elements)
      } else pending += elements // if we still have something scheduled we must not produce synchronously
    } finally recursionLevel -= 1
  }

  private def produce(elements: Long): Unit =
    if (elements > maxSyncBatchSize) {
      pushNext(maxSyncBatchSize)
      schedule(elements - maxSyncBatchSize)
    } else {
      pushNext(elements.toInt)
      pending = 0
    }

  private def schedule(newPending: Long): Unit = {
    pending = newPending
    executor.execute(
      new Runnable {
        @tailrec def run(): Unit =
          if (locked.compareAndSet(false, true)) {
            try produce(pending)
            finally locked.set(false)
          } else run()
      })
  }

  protected def shutdown(completed: Boolean): Unit = cancelUpstream()
  protected def cancelUpstream(): Unit = pending = 0

  // outside Publisher interface, can potentially called from another thread,
  // so we need to wrap with synchronization
  @tailrec final override def subscribe(subscriber: spi.Subscriber[T]): Unit =
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