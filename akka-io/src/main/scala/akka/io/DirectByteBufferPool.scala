package akka.io

import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import annotation.tailrec

trait WithBufferPool {
  def tcp: TcpExt

  def acquireBuffer(): ByteBuffer =
    tcp.bufferPool.acquire()

  def acquireBuffer(size: Int): ByteBuffer =
    tcp.bufferPool.acquire(size)

  def releaseBuffer(buffer: ByteBuffer) =
    tcp.bufferPool.release(buffer)
}

/**
 * A buffer pool which keeps direct buffers of a specified default size.
 * If a buffer bigger than the default size is requested it is created
 * but will not be pooled on release.
 *
 * This implementation is very loosely based on the one from Netty.
 */
class DirectByteBufferPool(bufferSize: Int, maxPoolSize: Int) {
  private val Unlocked = 0
  private val Locked = 1

  private[this] val state = new AtomicInteger(Unlocked)
  @volatile private[this] var pool: List[ByteBuffer] = Nil
  @volatile private[this] var poolSize: Int = 0

  private def allocate(size: Int): ByteBuffer =
    ByteBuffer.allocateDirect(size)

  def acquire(size: Int = bufferSize): ByteBuffer = {
    if (poolSize == 0 || size > bufferSize) allocate(size)
    else takeBufferFromPool()
  }

  def release(buf: ByteBuffer): Unit =
    if (buf.capacity() <= bufferSize && poolSize < maxPoolSize)
      addBufferToPool(buf)

  // TODO: check whether limiting the spin count in the following two methods is beneficial
  // (e.g. never limit more than 1000 times), since both methods could fall back to not
  // using the buffer at all (take fallback: create a new buffer, add fallback: just drop)

  @tailrec
  private def takeBufferFromPool(): ByteBuffer =
    if (state.compareAndSet(Unlocked, Locked))
      try pool match {
        case Nil ⇒ allocate(bufferSize) // we have no more buffer available, so create a new one
        case buf :: tail ⇒
          pool = tail
          poolSize -= 1
          buf
      } finally state.set(Unlocked)
    else takeBufferFromPool() // spin while locked

  @tailrec
  private def addBufferToPool(buf: ByteBuffer): Unit =
    if (state.compareAndSet(Unlocked, Locked)) {
      buf.clear() // ensure that we never have dirty buffers in the pool
      pool = buf :: pool
      poolSize += 1
      state.set(Unlocked)
    } else addBufferToPool(buf) // spin while locked
}
