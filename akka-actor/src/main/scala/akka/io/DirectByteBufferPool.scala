/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.io

import java.nio.ByteBuffer

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
  private[this] val pool: Array[ByteBuffer] = new Array[ByteBuffer](maxPoolEntries)
  private[this] var buffersInPool: Int = 0

  def acquire(): ByteBuffer =
    takeBufferFromPool()

  def release(buf: ByteBuffer): Unit =
    offerBufferToPool(buf)

  private def allocate(size: Int): ByteBuffer =
    ByteBuffer.allocateDirect(size)

  private final def takeBufferFromPool(): ByteBuffer = {
    val buffer = pool.synchronized {
      if (buffersInPool > 0) {
        buffersInPool -= 1
        pool(buffersInPool)
      } else null
    }

    // allocate new and clear outside the lock
    if (buffer == null)
      allocate(defaultBufferSize)
    else {
      buffer.clear()
      buffer
    }
  }

  private final def offerBufferToPool(buf: ByteBuffer): Unit =
    pool.synchronized {
      if (buffersInPool < maxPoolEntries) {
        pool(buffersInPool) = buf
        buffersInPool += 1
      } // else let the buffer be gc'd
    }
}
