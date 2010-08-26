/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import concurrent.forkjoin.LinkedTransferQueue
import java.util.concurrent.{TimeUnit, Semaphore}
import java.util.Iterator
import se.scalablesolutions.akka.util.Logger

class BoundedTransferQueue[E <: AnyRef](
        val capacity: Int,
        val pushTimeout: Long,
        val pushTimeUnit: TimeUnit)
      extends LinkedTransferQueue[E] {
  require(capacity > 0)
  require(pushTimeout > 0)
  require(pushTimeUnit ne null)
  
  protected val guard = new Semaphore(capacity)

  //Enqueue an item within the push timeout (acquire Semaphore)
  protected def enq(f: => Boolean): Boolean = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      val result = try {
        f
      } catch {
        case e =>
          guard.release //If something broke, release
          throw e
      }
      if (!result) guard.release //Didn't add anything
      result
    } else
      false
  }

  //Dequeue an item (release Semaphore)
  protected def deq(e: E): E = {
    if (e ne null) guard.release //Signal removal of item
    e
  }

  override def take(): E = deq(super.take)
  override def poll(): E = deq(super.poll)
  override def poll(timeout: Long, unit: TimeUnit): E = deq(super.poll(timeout,unit))

  override def remainingCapacity = guard.availablePermits

  override def remove(o: AnyRef): Boolean = {
    if (super.remove(o)) {
      guard.release
      true
    } else {
      false
    }
  }

  override def offer(e: E): Boolean =
    enq(super.offer(e))

  override def offer(e: E, timeout: Long, unit: TimeUnit): Boolean =
    enq(super.offer(e,timeout,unit))

  override def add(e: E): Boolean =
    enq(super.add(e))

  override def put(e :E): Unit =
    enq({ super.put(e); true })

  override def tryTransfer(e: E): Boolean =
    enq(super.tryTransfer(e))

  override def tryTransfer(e: E, timeout: Long, unit: TimeUnit): Boolean =
    enq(super.tryTransfer(e,timeout,unit))
  
  override def transfer(e: E): Unit =
    enq({ super.transfer(e); true })

  override def iterator: Iterator[E] = {
    val it = super.iterator
    new Iterator[E] {
      def hasNext = it.hasNext
      def next = it.next
      def remove {
        it.remove
        guard.release //Assume remove worked if no exception was thrown
      }
    }
  }
}