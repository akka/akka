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

  override def take(): E = {
    val e = super.take
    if (e ne null) guard.release
    e
  }

  override def poll(): E = {
    val e = super.poll
    if (e ne null) guard.release
    e
  }

  override def poll(timeout: Long, unit: TimeUnit): E = {
    val e = super.poll(timeout,unit)
    if (e ne null) guard.release
    e
  }

  override def remainingCapacity = guard.availablePermits

  override def remove(o: AnyRef): Boolean = {
    if (super.remove(o)) {
      guard.release
      true
    } else {
      false
    }
  }

  override def offer(e: E): Boolean = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      val result = try {
        super.offer(e)
      } catch {
        case e => guard.release; throw e
      }
      if (!result) guard.release
      result
    } else
      false
  }

  override def offer(e: E, timeout: Long, unit: TimeUnit): Boolean = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      val result = try {
        super.offer(e,timeout,unit)
      } catch {
        case e => guard.release; throw e
      }
      if (!result) guard.release
      result
    } else
      false
  }

  override def add(e: E): Boolean = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      val result = try {
        super.add(e)
      } catch {
        case e => guard.release; throw e
      }
      if (!result) guard.release
      result
    } else
      false
  }

  override def put(e :E): Unit = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      try {
        super.put(e)
      } catch {
        case e => guard.release; throw e
      }
    }
  }

  override def tryTransfer(e: E): Boolean = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      val result = try {
        super.tryTransfer(e)
      } catch {
        case e => guard.release; throw e
      }
      if (!result) guard.release
      result
    } else
      false
  }

  override def tryTransfer(e: E, timeout: Long, unit: TimeUnit): Boolean = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      val result = try {
        super.tryTransfer(e,timeout,unit)
      } catch {
        case e => guard.release; throw e
      }
      if (!result) guard.release
      result
    } else
      false
  }
  
  override def transfer(e: E): Unit = {
    if (guard.tryAcquire(pushTimeout,pushTimeUnit)) {
      try {
        super.transfer(e)
      } catch {
        case e => guard.release; throw e
      }
    }
  }

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