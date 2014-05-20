/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

/**
 * INTERNAL API
 */
private[akka] object FixedSizeBuffer {

  /**
   * INTERNAL API
   *
   * Returns a fixed size buffer backed by an array. The buffer implementation DOES NOT check agains overflow or
   * underflow, it is the responsibility of the user to track or check the capacity of the buffer before enqueueing
   * dequeueing or dropping.
   *
   * Returns a specialized instance for power-of-two sized buffers.
   */
  def apply(size: Int): FixedSizeBuffer =
    if (((size - 1) & size) == 0) new PowerOfTwoFixedSizeBuffer(size)
    else new ModuloFixedSizeBuffer(size)

  sealed abstract class FixedSizeBuffer(val size: Int) {
    protected var readIdx = 0
    protected var writeIdx = 0
    private var remainingCapacity = size
    private val buffer = Array.ofDim[Any](size)

    protected def incWriteIdx(): Unit
    protected def decWriteIdx(): Unit
    protected def incReadIdx(): Unit

    def isFull: Boolean = remainingCapacity == 0
    def isEmpty: Boolean = remainingCapacity == size

    def enqueue(elem: Any): Unit = {
      buffer(writeIdx) = elem
      incWriteIdx()
      remainingCapacity -= 1
    }

    def dequeue(): Any = {
      val result = buffer(readIdx)
      dropHead()
      result
    }

    def clear(): Unit = {
      java.util.Arrays.fill(buffer.asInstanceOf[Array[Object]], null)
      readIdx = 0
      writeIdx = 0
      remainingCapacity = size
    }

    def dropHead(): Unit = {
      buffer(readIdx) = null
      incReadIdx()
      remainingCapacity += 1
    }

    def dropTail(): Unit = {
      decWriteIdx()
      //buffer(writeIdx) = null
      remainingCapacity += 1
    }
  }

  private final class ModuloFixedSizeBuffer(_size: Int) extends FixedSizeBuffer(_size) {
    override protected def incReadIdx(): Unit = readIdx = (readIdx + 1) % size
    override protected def decWriteIdx(): Unit = writeIdx = (writeIdx + size - 1) % size
    override protected def incWriteIdx(): Unit = writeIdx = (writeIdx + 1) % size
  }

  private final class PowerOfTwoFixedSizeBuffer(_size: Int) extends FixedSizeBuffer(_size) {
    private val Mask = size - 1
    override protected def incReadIdx(): Unit = readIdx = (readIdx + 1) & Mask
    override protected def decWriteIdx(): Unit = writeIdx = (writeIdx - 1) & Mask
    override protected def incWriteIdx(): Unit = writeIdx = (writeIdx + 1) & Mask
  }

}

