/*
 * Copyright (C) 2009-2016 Mathias Doenitz, Alexander Myltsev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.parboiled2

import scala.annotation.tailrec
import akka.shapeless._

/**
 * A mutable untyped stack of values.
 * In most cases you won't have to access its API directly since parboiled2's DSL
 * should allow you a safer and easier way to interact with the stack.
 * However, in some cases, when you know what you are doing, direct access can be helpful.
 */
class ValueStack private[parboiled2] (initialSize: Int, maxSize: Int) extends Iterable[Any] {

  private[this] var buffer = new Array[Any](initialSize)
  private[this] var _size = 0

  private[parboiled2] def size_=(newSize: Int): Unit = _size = newSize

  /**
   * The number of elements currently on the stack.
   */
  override def size: Int = _size

  /**
   * True if no elements are currently on the stack.
   */
  override def isEmpty: Boolean = _size == 0

  /**
   * Removes all elements from the stack.
   */
  def clear(): Unit = _size = 0

  /**
   * Puts the given value onto the stack.
   * Throws a `ValueStackOverflowException` if the stack has no more space available.
   */
  def push(value: Any): Unit = {
    val oldSize = _size
    val newSize = oldSize + 1
    ensureSize(newSize)
    buffer(oldSize) = value
    _size = newSize
  }

  /**
   * Puts the given HList of values onto the stack.
   * Throws a `ValueStackOverflowException` if the stack has no more space available.
   */
  @tailrec final def pushAll(hlist: HList): Unit =
    hlist match {
      case akka.shapeless.::(head, tail) ⇒
        push(head)
        pushAll(tail)
      case HNil ⇒
    }

  /**
   * Inserts the given value into the stack `down` elements below the current
   * top element. `insert(0, 'x')` is therefore equal to `push('x')`.
   * Throws a `ValueStackOverflowException` if the stack has no more space available.
   * Throws a `ValueStackUnderflowException` if `down > size`.
   * Throws an `IllegalArgumentException` is `down` is negative.
   */
  def insert(down: Int, value: Any): Unit =
    math.signum(down) match {
      case -1 ⇒ throw new IllegalArgumentException("`down` must not be negative")
      case 0  ⇒ push(value)
      case 1 ⇒
        if (down > _size) throw new ValueStackUnderflowException
        val newSize = _size + 1
        ensureSize(newSize)
        val targetIx = _size - down
        System.arraycopy(buffer, targetIx, buffer, targetIx + 1, down)
        buffer(targetIx) = value
        _size = newSize
    }

  /**
   * Removes the top element from the stack and returns it.
   * Throws a `ValueStackUnderflowException` if the stack is empty.
   */
  def pop(): Any =
    if (_size > 0) {
      val newSize = _size - 1
      _size = newSize
      buffer(newSize)
    } else throw new ValueStackUnderflowException

  /**
   * Removes the element `down` elements below the current top element from the stack
   * and returns it. `pullOut(0)` is therefore equal to `pop()`.
   * Throws a `ValueStackUnderflowException` if `down >= size`.
   * Throws an `IllegalArgumentException` is `down` is negative.
   */
  def pullOut(down: Int): Any =
    math.signum(down) match {
      case -1 ⇒ throw new IllegalArgumentException("`down` must not be negative")
      case 0  ⇒ pop()
      case 1 ⇒
        if (down >= _size) throw new ValueStackUnderflowException
        val newSize = _size - 1
        val targetIx = newSize - down
        val result = buffer(targetIx)
        System.arraycopy(buffer, targetIx + 1, buffer, targetIx, down)
        _size = newSize
        result
    }

  /**
   * Returns the top element without removing it.
   * Throws a `ValueStackUnderflowException` if the stack is empty.
   */
  def peek: Any =
    if (_size > 0) buffer(_size - 1)
    else throw new ValueStackUnderflowException

  /**
   * Returns the element `down` elements below the current top element without removing it.
   * `peek(0)` is therefore equal to `peek()`.
   * Throws a `ValueStackUnderflowException` if `down >= size`.
   * Throws an `IllegalArgumentException` is `down` is negative.
   */
  def peek(down: Int): Any =
    math.signum(down) match {
      case -1 ⇒ throw new IllegalArgumentException("`down` must not be negative")
      case 0  ⇒ peek
      case 1 ⇒
        if (down >= _size) throw new ValueStackUnderflowException
        else buffer(_size - down - 1)
    }

  /**
   * Replaces the element `down` elements below the current top element with the given one.
   * Throws a `ValueStackUnderflowException` if `down >= size`.
   * Throws an `IllegalArgumentException` if `down` is negative.
   */
  def poke(down: Int, value: Any): Unit = {
    if (down >= _size) throw new ValueStackUnderflowException
    require(down >= 0, "`down` must be >= 0")
    buffer(_size - down - 1) = value
  }

  /**
   * Swaps the top 2 stack elements.
   * Throws a `ValueStackUnderflowException` if `size < 2`.
   */
  def swap(): Unit = {
    if (_size < 2) throw new ValueStackUnderflowException
    val temp = buffer(_size - 1)
    buffer(_size - 1) = buffer(_size - 2)
    buffer(_size - 2) = temp
  }

  /**
   * Swaps the top 3 stack elements.
   * Throws a `ValueStackUnderflowException` if `size < 3`.
   */
  def swap3(): Unit = {
    if (_size < 3) throw new ValueStackUnderflowException
    val temp = buffer(_size - 1)
    buffer(_size - 1) = buffer(_size - 3)
    buffer(_size - 3) = temp
  }

  /**
   * Swaps the top 4 stack elements.
   * Throws a `ValueStackUnderflowException` if `size < 4`.
   */
  def swap4(): Unit = {
    if (_size < 4) throw new ValueStackUnderflowException
    var temp = buffer(_size - 1)
    buffer(_size - 1) = buffer(_size - 4)
    buffer(_size - 4) = temp
    temp = buffer(_size - 2)
    buffer(_size - 2) = buffer(_size - 3)
    buffer(_size - 3) = temp
  }

  /**
   * Swaps the top 5 stack elements.
   * Throws a `ValueStackUnderflowException` if `size < 5`.
   */
  def swap5(): Unit = {
    if (_size < 5) throw new ValueStackUnderflowException
    var temp = buffer(_size - 1)
    buffer(_size - 1) = buffer(_size - 5)
    buffer(_size - 5) = temp
    temp = buffer(_size - 2)
    buffer(_size - 2) = buffer(_size - 4)
    buffer(_size - 4) = temp
  }

  /**
   * Returns all current stack elements as a new array.
   */
  def toArray: Array[Any] = {
    val a = new Array[Any](_size)
    System.arraycopy(buffer, 0, a, 0, _size)
    a
  }

  /**
   * Copies all elements between the given `start` (inclusive) and `end` (exclusive)
   * indices into an HList that is prepended to the given tail.
   * Throws an `IllegalArgumentException` if `start < 0 || start > end`.
   * Throws a `ValueStackUnderflowException` if `end > size`.
   */
  @tailrec final def toHList[L <: HList](start: Int = 0, end: Int = _size, prependTo: HList = HNil): L = {
    require(0 <= start && start <= end, "`start` must be >= 0 and <= `end`")
    if (start == end) prependTo.asInstanceOf[L]
    else toHList[L](start, end - 1, buffer(end - 1) :: prependTo)
  }

  /**
   * Creates a string representation of the current value stack contents.
   * Mostly useful for debugging.
   */
  def show: String = mkString("[", ", ", "]")

  /**
   * Returns an iterator that iterates over a *snapshot* of the stack elements
   * at the time of this method call. I.e. subsequent mutations are not visible
   * to the iterator.
   */
  def iterator: Iterator[Any] = toArray.iterator

  private def ensureSize(requiredSize: Int): Unit =
    if (buffer.length < requiredSize)
      if (requiredSize <= maxSize) {
        val newSize = math.min(math.max(buffer.length * 2, requiredSize), maxSize)
        val newBuffer = new Array[Any](newSize)
        System.arraycopy(buffer, 0, newBuffer, 0, _size)
        buffer = newBuffer
      } else throw new ValueStackOverflowException
}

class ValueStackOverflowException extends RuntimeException
class ValueStackUnderflowException extends RuntimeException