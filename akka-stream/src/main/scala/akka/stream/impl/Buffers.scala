/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.{ util ⇒ ju }
import akka.stream._

/**
 * INTERNAL API
 */
private[akka] trait Buffer[T] {
  def capacity: Int
  def used: Int
  def isFull: Boolean
  def isEmpty: Boolean
  def nonEmpty: Boolean

  def enqueue(elem: T): Unit
  def dequeue(): T

  def peek(): T
  def clear(): Unit
  def dropHead(): Unit
  def dropTail(): Unit
}

private[akka] object Buffer {
  val FixedQueueSize = 128
  val FixedQueueMask = 127

  def apply[T](size: Int, settings: ActorMaterializerSettings): Buffer[T] =
    apply(size, settings.maxFixedBufferSize)

  def apply[T](size: Int, materializer: Materializer): Buffer[T] =
    materializer match {
      case m: ActorMaterializer ⇒ apply(size, m.settings.maxFixedBufferSize)
      case _                    ⇒ apply(size, 1000000000)
    }

  def apply[T](size: Int, max: Int): Buffer[T] =
    if (size < FixedQueueSize || size < max) FixedSizeBuffer(size)
    else new BoundedBuffer(size)
}

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

  sealed abstract class FixedSizeBuffer[T](val capacity: Int) extends Buffer[T] {
    override def toString = s"Buffer($capacity, $readIdx, $writeIdx)(${(readIdx until writeIdx).map(get).mkString(", ")})"
    private val buffer = new Array[AnyRef](capacity)

    protected var readIdx = 0L
    protected var writeIdx = 0L
    def used: Int = (writeIdx - readIdx).toInt

    def isFull: Boolean = used == capacity
    def isEmpty: Boolean = used == 0
    def nonEmpty: Boolean = used != 0

    def enqueue(elem: T): Unit = {
      put(writeIdx, elem, false)
      writeIdx += 1
    }

    // for the maintenance parameter see dropHead
    protected def toOffset(idx: Long, maintenance: Boolean): Int

    private def put(idx: Long, elem: T, maintenance: Boolean): Unit = buffer(toOffset(idx, maintenance)) = elem.asInstanceOf[AnyRef]
    private def get(idx: Long): T = buffer(toOffset(idx, false)).asInstanceOf[T]

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
      /*
       * this is the only place where readIdx is advanced, so give ModuloFixedSizeBuffer
       * a chance to prevent its fatal wrap-around
       */
      put(readIdx, null.asInstanceOf[T], true)
      readIdx += 1
    }

    def dropTail(): Unit = {
      writeIdx -= 1
      put(writeIdx, null.asInstanceOf[T], false)
    }
  }

  private[akka] final class ModuloFixedSizeBuffer[T](_size: Int) extends FixedSizeBuffer[T](_size) {
    override protected def toOffset(idx: Long, maintenance: Boolean): Int = {
      if (maintenance && readIdx > Int.MaxValue) {
        /*
         * In order to be able to run perpetually we must ensure that the counters
         * don’t overrun into negative territory, so set them back by as many multiples
         * of the capacity as possible when both are above Int.MaxValue.
         */
        val shift = Int.MaxValue - (Int.MaxValue % capacity)
        readIdx -= shift
        writeIdx -= shift
      }
      (idx % capacity).toInt
    }
  }

  private[akka] final class PowerOfTwoFixedSizeBuffer[T](_size: Int) extends FixedSizeBuffer[T](_size) {
    private val Mask = capacity - 1
    override protected def toOffset(idx: Long, maintenance: Boolean): Int = idx.toInt & Mask
  }

}

/**
 * INTERNAL API
 */
private[akka] final class BoundedBuffer[T](val capacity: Int) extends Buffer[T] {

  def used: Int = q.used
  def isFull: Boolean = q.isFull
  def isEmpty: Boolean = q.isEmpty
  def nonEmpty: Boolean = q.nonEmpty

  def enqueue(elem: T): Unit = q.enqueue(elem)
  def dequeue(): T = q.dequeue()

  def peek(): T = q.peek()
  def clear(): Unit = q.clear()
  def dropHead(): Unit = q.dropHead()
  def dropTail(): Unit = q.dropTail()

  private final class FixedQueue extends Buffer[T] {
    import Buffer._

    private val queue = new Array[AnyRef](FixedQueueSize)
    private var head = 0
    private var tail = 0

    override def capacity = BoundedBuffer.this.capacity
    override def used = tail - head
    override def isFull = used == capacity
    override def isEmpty = tail == head
    override def nonEmpty = tail != head

    override def enqueue(elem: T): Unit =
      if (tail - head == FixedQueueSize) {
        val queue = new DynamicQueue(head)
        while (nonEmpty) {
          queue.enqueue(dequeue())
        }
        q = queue
        queue.enqueue(elem)
      } else {
        queue(tail & FixedQueueMask) = elem.asInstanceOf[AnyRef]
        tail += 1
      }
    override def dequeue(): T = {
      val pos = head & FixedQueueMask
      val ret = queue(pos).asInstanceOf[T]
      queue(pos) = null
      head += 1
      ret
    }

    override def peek(): T =
      if (tail == head) null.asInstanceOf[T]
      else queue(head & FixedQueueMask).asInstanceOf[T]
    override def clear(): Unit =
      while (nonEmpty) {
        dequeue()
      }
    override def dropHead(): Unit = dequeue()
    override def dropTail(): Unit = {
      tail -= 1
      queue(tail & FixedQueueMask) = null
    }
  }

  private final class DynamicQueue(startIdx: Int) extends ju.LinkedList[T] with Buffer[T] {
    override def capacity = BoundedBuffer.this.capacity
    override def used = size
    override def isFull = size == capacity
    override def nonEmpty = !isEmpty()

    override def enqueue(elem: T): Unit = add(elem)
    override def dequeue(): T = remove()

    override def dropHead(): Unit = remove()
    override def dropTail(): Unit = removeLast()
  }

  private var q: Buffer[T] = new FixedQueue
}
