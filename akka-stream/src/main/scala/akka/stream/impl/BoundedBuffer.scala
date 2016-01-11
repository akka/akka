/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.{ util ⇒ ju }

/**
 * INTERNAL API
 */
private[akka] trait Buffer[T] {
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
    final val Size = 16
    final val Mask = 15

    private val queue = new Array[AnyRef](Size)
    private var head = 0
    private var tail = 0

    override def used = tail - head
    override def isFull = used == capacity
    override def isEmpty = tail == head
    override def nonEmpty = tail != head

    override def enqueue(elem: T): Unit =
      if (tail - head == Size) {
        val queue = new DynamicQueue(head)
        while (nonEmpty) {
          queue.enqueue(dequeue())
        }
        q = queue
        queue.enqueue(elem)
      } else {
        queue(tail & Mask) = elem.asInstanceOf[AnyRef]
        tail += 1
      }
    override def dequeue(): T = {
      val pos = head & Mask
      val ret = queue(pos).asInstanceOf[T]
      queue(pos) = null
      head += 1
      ret
    }

    override def peek(): T =
      if (tail == head) null.asInstanceOf[T]
      else queue(head & Mask).asInstanceOf[T]
    override def clear(): Unit =
      while (nonEmpty) {
        dequeue()
      }
    override def dropHead(): Unit = dequeue()
    override def dropTail(): Unit = {
      tail -= 1
      queue(tail & Mask) = null
    }
  }

  private final class DynamicQueue(startIdx: Int) extends ju.LinkedList[T] with Buffer[T] {
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
