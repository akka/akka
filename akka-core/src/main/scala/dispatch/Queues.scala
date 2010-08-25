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
  
  protected val guard = new Semaphore(capacity)
  
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

  protected def deq(f: => E): E = {
    val result: E = f
    if (result ne null) guard.release
    result
  }

  override def take(): E = deq(super.take)
  override def poll(): E = deq(super.poll)
  override def poll(timeout: Long, unit: TimeUnit): E = deq(super.poll(timeout,unit))

  override def remainingCapacity = guard.availablePermits
  override def remove(o: AnyRef): Boolean = deq({
    (if (o.isInstanceOf[E] && super.remove(o)) o else null).asInstanceOf[E]
  }) ne null

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