package akka.io

import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import annotation.tailrec
import java.lang.ref.SoftReference

trait WithBufferPool {
  def tcp: TcpExt

  def acquireBuffer(): ByteBuffer =
    tcp.bufferPool.acquire()

  def releaseBuffer(buffer: ByteBuffer) =
    tcp.bufferPool.release(buffer)
}

trait BufferPool {
  def acquire(): ByteBuffer
  def release(buf: ByteBuffer): Unit
}

/**
 * A buffer pool which keeps a free list of direct buffers of a specified default
 * size in a simple fixed size stack.
 *
 * If the stack is full a buffer offered back is not kept but will be let for
 * being freed by normal garbage collection.
 */
private[akka] class DirectByteBufferPool(defaultBufferSize: Int, maxPoolEntries: Int) extends BufferPool {
  private[this] val locked = new AtomicBoolean(false)
  private[this] val pool: Array[SoftReference[ByteBuffer]] = new Array[SoftReference[ByteBuffer]](maxPoolEntries)
  private[this] var buffersInPool: Int = 0

  def acquire(): ByteBuffer = {
    val buffer = takeBufferFromPool()

    // allocate new buffer and clear outside the lock
    if (buffer == null)
      allocate(defaultBufferSize)
    else {
      buffer.clear()
      buffer
    }
  }

  def release(buf: ByteBuffer): Unit =
    offerBufferToPool(buf)

  private def allocate(size: Int): ByteBuffer =
    ByteBuffer.allocateDirect(size)

  @tailrec
  private[this] final def takeBufferFromPool(): ByteBuffer = {
    @tailrec def findBuffer(): ByteBuffer =
      if (buffersInPool > 0) {
        buffersInPool -= 1
        val buf = pool(buffersInPool).get()

        if (buf != null) buf
        else findBuffer()
      } else null

    if (locked.compareAndSet(false, true))
      try findBuffer() finally locked.set(false)
    else takeBufferFromPool() // spin while locked
  }

  @tailrec
  private final def offerBufferToPool(buf: ByteBuffer): Unit =
    if (locked.compareAndSet(false, true))
      try if (buffersInPool < maxPoolEntries) {
        pool(buffersInPool) = new SoftReference(buf)
        buffersInPool += 1
      } // else let the buffer be gc'd
      finally locked.set(false)
    else offerBufferToPool(buf) // spin while locked
}
