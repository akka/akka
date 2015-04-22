/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

trait BufferPool {
  def acquire(): ByteBuffer
  def release(buf: ByteBuffer)
}

/**
 * INTERNAL API
 *
 * A buffer pool which keeps a free list of direct buffers of a specified default
 * size in a simple fixed size stack.
 *
 * If the stack is full the buffer is de-referenced and available to be
 * freed by normal garbage collection.
 *
 * Using a direct ByteBuffer when dealing with NIO operations has been proven
 * to be faster than wrapping on-heap Arrays. There is ultimately no performance
 * benefit to wrapping in-heap JVM data when writing with NIO.
 */
private[akka] class DirectByteBufferPool(defaultBufferSize: Int, maxPoolEntries: Int) extends BufferPool {
  private[this] val lock = new ReentrantLock
  private[this] val pool = new Array[ByteBuffer](maxPoolEntries)
  private[this] var buffersInPool = 0

  def acquire(): ByteBuffer =
    takeBufferFromPool()

  def release(buf: ByteBuffer): Unit =
    offerBufferToPool(buf)

  private def allocate(size: Int): ByteBuffer =
    ByteBuffer.allocateDirect(size)

  private final def takeBufferFromPool(): ByteBuffer = {
    var buffer: ByteBuffer = null

    if (lock.tryLock(1, TimeUnit.MILLISECONDS))
      try
        if (buffersInPool > 0) {
          buffersInPool -= 1
          buffer = pool(buffersInPool)
        }
      finally lock.unlock()

    // allocate new and clear outside the lock
    if (buffer == null)
      allocate(defaultBufferSize)
    else {
      buffer.clear()
      buffer
    }
  }

  private final def offerBufferToPool(buf: ByteBuffer): Unit =
    if (lock.tryLock(1, TimeUnit.MILLISECONDS))
      try
        if (buffersInPool < maxPoolEntries) {
          pool(buffersInPool) = buf
          buffersInPool += 1
        } // else let the buffer be gc'd
      finally lock.unlock()
}
