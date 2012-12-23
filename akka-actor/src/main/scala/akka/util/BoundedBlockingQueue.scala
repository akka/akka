/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.{ TimeUnit, BlockingQueue }
import java.util.{ AbstractQueue, Queue, Collection, Iterator }
import annotation.tailrec

/**
 * BoundedBlockingQueue wraps any Queue and turns the result into a BlockingQueue with a limited capacity
 * @param maxCapacity - the maximum capacity of this Queue, needs to be > 0
 * @param backing - the backing Queue
 * @tparam E - The type of the contents of this Queue
 */
class BoundedBlockingQueue[E <: AnyRef](
  val maxCapacity: Int, private val backing: Queue[E]) extends AbstractQueue[E] with BlockingQueue[E] {

  backing match {
    case null ⇒ throw new IllegalArgumentException("Backing Queue may not be null")
    case b: BlockingQueue[_] ⇒
      require(maxCapacity > 0)
      require(b.size() == 0)
      require(b.remainingCapacity >= maxCapacity)
    case b: Queue[_] ⇒
      require(b.size() == 0)
      require(maxCapacity > 0)
  }

  protected final val lock = new ReentrantReadWriteLock(false)
  protected final val readLock = lock.readLock
  protected final val writeLock = lock.writeLock

  private val notEmpty = writeLock.newCondition()
  private val notFull = writeLock.newCondition()

  def put(e: E) { //Blocks until not full
    if (e eq null) throw new NullPointerException
    writeLock.lock()
    try {
      while (backing.size() == maxCapacity)
        notFull.await()
      require(backing.offer(e))
      notEmpty.signal()
    } finally {
      writeLock.unlock()
    }
  }

  def take(): E = { //Blocks until not empty
    writeLock.lockInterruptibly()
    try {
      while (backing.size() == 0)
        notEmpty.await()
      val e = backing.poll()
      require(e ne null)
      notFull.signal()
      e
    } finally {
      writeLock.unlock()
    }
  }

  def offer(e: E): Boolean = { //Tries to do it immediately, if fail return false
    if (e eq null) throw new NullPointerException
    writeLock.lock()
    try {
      if (backing.size() == maxCapacity) false
      else {
        require(backing.offer(e)) //Should never fail
        notEmpty.signal()
        true
      }
    } finally {
      writeLock.unlock()
    }
  }

  def offer(e: E, timeout: Long, unit: TimeUnit): Boolean = { //Tries to do it within the timeout, return false if fail
    if (e eq null) throw new NullPointerException
    val nanos = unit.toNanos(timeout)
    writeLock.lockInterruptibly()
    try {
      @tailrec def awaitNotFull(ns: Long): Boolean =
        if (backing.size() == maxCapacity) {
          if (ns > 0) awaitNotFull(notFull.awaitNanos(ns))
          else false
        } else true

      if (awaitNotFull(nanos)) {
        require(backing.offer(e)) //Should never fail
        notEmpty.signal()
        true
      } else false
    } finally {
      writeLock.unlock()
    }
  }

  def poll(timeout: Long, unit: TimeUnit): E = { //Tries to do it within the timeout, returns null if fail
    var nanos = unit.toNanos(timeout)
    writeLock.lockInterruptibly()
    try {
      var result: E = null.asInstanceOf[E]
      var hasResult = false
      while (!hasResult) {
        hasResult = backing.poll() match {
          case null if nanos <= 0 ⇒
            result = null.asInstanceOf[E]
            true
          case null ⇒
            try {
              nanos = notEmpty.awaitNanos(nanos)
            } catch {
              case ie: InterruptedException ⇒
                notEmpty.signal()
                throw ie
            }
            false
          case e ⇒
            notFull.signal()
            result = e
            true
        }
      }
      result
    } finally {
      writeLock.unlock()
    }
  }

  def poll(): E = { //Tries to remove the head of the queue immediately, if fail, return null
    writeLock.lock()
    try {
      backing.poll() match {
        case null ⇒ null.asInstanceOf[E]
        case e ⇒
          notFull.signal()
          e
      }
    } finally {
      writeLock.unlock()
    }
  }

  override def remove(e: AnyRef): Boolean = { //Tries to do it immediately, if fail, return false
    if (e eq null) throw new NullPointerException
    writeLock.lock()
    try {
      if (backing remove e) {
        notFull.signal()
        true
      } else false
    } finally {
      writeLock.unlock()
    }
  }

  override def contains(e: AnyRef): Boolean = {
    if (e eq null) throw new NullPointerException
    readLock.lock()
    try {
      backing contains e
    } finally {
      readLock.unlock()
    }
  }

  override def clear() {
    writeLock.lock()
    try {
      backing.clear
    } finally {
      writeLock.unlock()
    }
  }

  def remainingCapacity(): Int = {
    readLock.lock()
    try {
      maxCapacity - backing.size()
    } finally {
      readLock.unlock()
    }
  }

  def size(): Int = {
    readLock.lock()
    try {
      backing.size()
    } finally {
      readLock.unlock()
    }
  }

  def peek(): E = {
    readLock.lock()
    try {
      backing.peek()
    } finally {
      readLock.unlock()
    }
  }

  def drainTo(c: Collection[_ >: E]): Int = drainTo(c, Int.MaxValue)

  def drainTo(c: Collection[_ >: E], maxElements: Int): Int = {
    if (c eq null) throw new NullPointerException
    if (c eq this) throw new IllegalArgumentException
    if (maxElements <= 0) 0
    else {
      writeLock.lock()
      try {
        @tailrec def drainOne(n: Int): Int =
          if (n < maxElements) {
            backing.poll() match {
              case null ⇒ n
              case e    ⇒ c add e; drainOne(n + 1)
            }
          } else n
        drainOne(0)
      } finally {
        writeLock.unlock()
      }
    }
  }

  override def containsAll(c: Collection[_]): Boolean = {
    readLock.lock()
    try {
      backing containsAll c
    } finally {
      readLock.unlock()
    }
  }

  override def removeAll(c: Collection[_]): Boolean = {
    writeLock.lock()
    try {
      if (backing.removeAll(c)) {
        val sz = backing.size()
        if (sz < maxCapacity) notFull.signal()
        if (sz > 0) notEmpty.signal()
        true
      } else false
    } finally {
      writeLock.unlock()
    }
  }

  override def retainAll(c: Collection[_]): Boolean = {
    writeLock.lock()
    try {
      if (backing.retainAll(c)) {
        val sz = backing.size()
        if (sz < maxCapacity) notFull.signal()
        if (sz > 0) notEmpty.signal()
        true
      } else false
    } finally {
      writeLock.unlock()
    }
  }

  def iterator(): Iterator[E] = {
    readLock.lock()
    try {
      val elements = backing.toArray
      new Iterator[E] {
        var at = 0
        var last = -1

        def hasNext(): Boolean = at < elements.length

        def next(): E = {
          if (at >= elements.length) throw new NoSuchElementException
          last = at
          at += 1
          elements(last).asInstanceOf[E]
        }

        def remove() {
          if (last < 0) throw new IllegalStateException
          val target = elements(last)
          last = -1 //To avoid 2 subsequent removes without a next in between
          writeLock.lock()
          try {
            @tailrec def removeTarget(i: Iterator[E] = backing.iterator()): Unit = if (i.hasNext) {
              if (i.next eq target) {
                i.remove()
                notFull.signal()
              } else removeTarget(i)
            }

            removeTarget()
          } finally {
            writeLock.unlock()
          }
        }
      }
    } finally {
      readLock.unlock()
    }
  }

  override def toArray(): Array[AnyRef] = {
    readLock.lock()
    try {
      backing.toArray
    } finally {
      readLock.unlock()
    }
  }

  override def isEmpty(): Boolean = {
    readLock.lock()
    try {
      backing.isEmpty()
    } finally {
      readLock.unlock()
    }
  }

  override def toArray[X](a: Array[X with AnyRef]) = {
    readLock.lock()
    try {
      backing.toArray[X](a)
    } finally {
      readLock.unlock()
    }
  }
}
