/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.driver

import java.nio.ByteBuffer

import akka.io.DirectByteBufferPool
import akka.util.ByteString

/**
 * INTERNAL API
 */
private[remote] object FrameBuffer {
  val FrameSize = 16
  val BufferSizeInFrames = 32
  val BufferSize = FrameSize * BufferSizeInFrames
  val BufferSizeMask = BufferSizeInFrames - 1
}

/**
 * INTERNAL API
 */
private[remote] final class FrameBuffer(val pool: DirectByteBufferPool) {
  import FrameBuffer._

  val buffer: ByteBuffer = pool.acquire()
  private var lastAllocation = 0
  private val frames: Array[Frame] = Array.tabulate(BufferSizeInFrames)(new Frame(this, _))
  private val allocated: Array[Boolean] = Array.ofDim(BufferSizeInFrames)

  def aquire(): Frame = {
    val wraparound = lastAllocation
    lastAllocation = (lastAllocation + 1) & BufferSizeMask
    while (allocated(lastAllocation) && lastAllocation != wraparound) lastAllocation = (lastAllocation + 1) & BufferSizeMask
    if (lastAllocation == wraparound) null
    else {
      allocated(lastAllocation) = true
      frames(lastAllocation)
    }
  }

  def release(frame: Frame): Unit = {
    allocated(frame.id) = false
  }

  def close(): Unit = {
    pool.release(buffer)
  }

}

/**
 * INTERNAL API
 */
private[remote] final class Frame(val owner: FrameBuffer, val id: Int) {
  import FrameBuffer._

  val buffer = {
    owner.buffer.position(id * FrameSize)
    owner.buffer.limit(id * FrameSize + FrameSize)
    owner.buffer.slice()
  }

  def release(): Unit = owner.release(this)

  def toByteString: ByteString = {
    ByteString.fromByteBuffer(buffer)
  }

  def putByteString(bs: ByteString): Unit = {
    buffer.clear()
    buffer.put(bs.toByteBuffer) // FIXME: toByteBuffer copies
  }
}
