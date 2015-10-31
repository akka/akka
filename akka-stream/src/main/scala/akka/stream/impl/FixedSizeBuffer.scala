/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.reflect.classTag
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
private[akka] object FixedSizeBuffer {

  /**
   * INTERNAL API
   *
   * Returns a fixed size buffer backed by an array. The buffer implementation DOES NOT check against overflow or
   * underflow, it is the responsibility of the user to track or check the capacity of the buffer before enqueueing
   * dequeueing or dropping.
   *
   * Returns a specialized instance for power-of-two sized buffers.
   */
  def apply[T](size: Int): FixedSizeBuffer[T] =
    if (size < 1) throw new IllegalArgumentException("size must be positive")
    else if (((size - 1) & size) == 0) new PowerOfTwoFixedSizeBuffer(size)
    else new ModuloFixedSizeBuffer(size)

  sealed abstract class FixedSizeBuffer[T](val size: Int) {
    override def toString = s"Buffer($size, $readIdx, $writeIdx)(${(readIdx until writeIdx).map(get).mkString(", ")})"
    private val buffer = new Array[AnyRef](size)

    protected var readIdx = 0
    protected var writeIdx = 0
    def used: Int = writeIdx - readIdx

    def isFull: Boolean = used == size
    def isEmpty: Boolean = used == 0

    def enqueue(elem: T): Int = {
      put(writeIdx, elem)
      val ret = writeIdx
      writeIdx += 1
      ret
    }

    protected def toOffset(idx: Int): Int

    def put(idx: Int, elem: T): Unit = buffer(toOffset(idx)) = elem.asInstanceOf[AnyRef]
    def get(idx: Int): T = buffer(toOffset(idx)).asInstanceOf[T]

    def peek(): T = get(readIdx)

    def dequeue(): T = {
      val result = get(readIdx)
      dropHead()
      result
    }

    def clear(): Unit = {
      java.util.Arrays.fill(buffer, null)
      readIdx = 0
      writeIdx = 0
    }

    def dropHead(): Unit = {
      put(readIdx, null.asInstanceOf[T])
      readIdx += 1
    }

    def dropTail(): Unit = {
      writeIdx -= 1
      put(writeIdx, null.asInstanceOf[T])
    }
  }

  private final class ModuloFixedSizeBuffer[T](_size: Int) extends FixedSizeBuffer[T](_size) {
    override protected def toOffset(idx: Int): Int = idx % size
  }

  private final class PowerOfTwoFixedSizeBuffer[T](_size: Int) extends FixedSizeBuffer[T](_size) {
    private val Mask = size - 1
    override protected def toOffset(idx: Int): Int = idx & Mask
  }

}
