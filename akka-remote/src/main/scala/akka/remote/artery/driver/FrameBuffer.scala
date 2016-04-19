/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.driver

import java.nio.{ ByteBuffer, ByteOrder }

import akka.io.DirectByteBufferPool
import akka.util.ByteString
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue

/**
 * INTERNAL API
 */
private[remote] object FrameBuffer {
  val FrameSize = 1024
  val BufferSizeInFrames = 1024
  val BufferSize = FrameSize * BufferSizeInFrames
  val BufferSizeMask = BufferSizeInFrames - 1
}

/**
 * INTERNAL API
 *
 */
private[remote] final class FrameBuffer(val pool: DirectByteBufferPool) {
  import FrameBuffer._

  val buffer: ByteBuffer = pool.acquire()

  // TODO: Bounded, array based queue, but no lazySets)
  private val availableFrames: ManyToOneConcurrentArrayQueue[Frame] = new ManyToOneConcurrentArrayQueue[Frame](BufferSizeInFrames)
  (0 until BufferSizeInFrames).foreach { id ⇒
    availableFrames.add(new Frame(this, id))
  }

  def acquire(): Frame = {
    val frame = availableFrames.poll()
    if (frame ne null) {
      frame.buffer.clear()
      frame.driverReleases = false
    }
    frame
  }

  def release(frame: Frame): Unit = availableFrames.add(frame)

  def close(): Unit = {
    pool.release(buffer)
  }

}

/**
 * INTERNAL API
 */
private[remote] final class Frame(val owner: FrameBuffer, val id: Int) {
  import FrameBuffer._

  @volatile var driverReleases = false

  val buffer = {
    owner.buffer.position(id * FrameSize)
    owner.buffer.limit(id * FrameSize + FrameSize)
    owner.buffer.slice()
  }
  buffer.order(ByteOrder.LITTLE_ENDIAN) // Be friendly to Intel :)

  def release(): Unit = owner.release(this)

  def toByteString: ByteString = {
    ByteString.fromByteBuffer(buffer)
  }

  def writeByteString(bs: ByteString): Unit = {
    buffer.put(bs.toByteBuffer) // FIXME: toByteBuffer copies
  }
}
