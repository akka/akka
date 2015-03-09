/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import akka.util.ByteString

import annotation.tailrec
import scala.util.control.NonFatal

trait BufferPool {
  def acquire(): ByteBuffer
  def release(buf: ByteBuffer)

  /**
   * Either produce a wrapped 0-copy ByteBuffer or copy the contents
   * into one of our pooled buffers.
   */
  def readOnlyOrAcquireAndCopy(from: ByteString): ByteBuffer = {
    if (from.canWrapAsByteBuffer) from.asByteBuffer
    else {
      val buffer: ByteBuffer = acquire()
      try {
        buffer.clear()
        from.copyToBuffer(buffer)
        buffer.flip()
        buffer
      } catch {
        case NonFatal(t) ⇒
          release(buffer)
          throw t
      }
    }
  }
}

/**
 * INTERNAL API
 *
 * A buffer pool which keeps a free list of direct buffers of a specified default
 * size in a simple fixed size stack.
 *
 * If the stack is full a buffer offered back is not kept but will be let for
 * being freed by normal garbage collection.
 */
private[akka] class DirectByteBufferPool(defaultBufferSize: Int, maxPoolEntries: Int) extends BufferPool {
  private[this] val locked = new AtomicBoolean(false)
  private[this] val pool: Array[ByteBuffer] = new Array[ByteBuffer](maxPoolEntries)
  private[this] var buffersInPool: Int = 0

  def acquire(): ByteBuffer =
    takeBufferFromPool()

  def release(buf: ByteBuffer): Unit =
    if (!buf.isReadOnly) offerBufferToPool(buf)

  private def allocate(size: Int): ByteBuffer =
    ByteBuffer.allocateDirect(size)

  def size = buffersInPool

  @tailrec
  private final def takeBufferFromPool(): ByteBuffer =
    if (locked.compareAndSet(false, true)) {
      val buffer =
        try if (buffersInPool > 0) {
          buffersInPool -= 1
          pool(buffersInPool)
        } else null
        finally locked.set(false)

      // allocate new and clear outside the lock
      if (buffer == null)
        allocate(defaultBufferSize)
      else {
        buffer.clear()
        buffer
      }
    } else takeBufferFromPool() // spin while locked

  @tailrec
  private final def offerBufferToPool(buf: ByteBuffer): Unit =
    if (locked.compareAndSet(false, true))
      try if (buffersInPool < maxPoolEntries) {
        pool(buffersInPool) = buf
        buffersInPool += 1
      } // else let the buffer be gc'd
      finally locked.set(false)
    else offerBufferToPool(buf) // spin while locked
}
