/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.nio.ByteBuffer

import scala.collection.IndexedSeqOptimized
import scala.collection.mutable.{ Builder, WrappedArray }
import scala.collection.immutable.{ IndexedSeq, VectorBuilder }
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.{ ListBuffer }
import scala.annotation.tailrec

import java.nio.ByteBuffer

abstract class ByteIterator extends BufferedIterator[Byte] {
  def isIdenticalTo(that: Iterator[Byte]): Boolean

  def len: Int

  def head: Byte

  def next(): Byte

  protected def clear(): Unit

  def ++(that: TraversableOnce[Byte]): ByteIterator =
    ByteArrayIterator(that.toArray)

  // *must* be overridden by derived classes
  override def clone: ByteIterator = null

  final override def duplicate = (this, clone)

  // *must* be overridden by derived classes
  override def take(n: Int): this.type = null

  // *must* be overridden by derived classes
  override def drop(n: Int): this.type = null

  final override def slice(from: Int, until: Int): this.type =
    drop(from).take(until - from)

  // *must* be overridden by derived classes
  override def takeWhile(p: Byte ⇒ Boolean): this.type = null

  // *must* be overridden by derived classes
  override def dropWhile(p: Byte ⇒ Boolean): this.type = null

  final override def span(p: Byte ⇒ Boolean): (ByteIterator, ByteIterator) = {
    val that = clone
    that.takeWhile(p)
    drop(that.len)
    (that, this)
  }

  final override def indexWhere(p: Byte ⇒ Boolean): Int = {
    var index = 0
    var found = false
    while (!found && hasNext) if (p(next())) { found = true } else { index += 1 }
    if (found) index else -1
  }

  final def indexOf(elem: Byte): Int = {
    var index = 0
    var found = false
    while (!found && hasNext) if (elem == next()) { found = true } else { index += 1 }
    if (found) index else -1
  }

  final override def indexOf[B >: Byte](elem: B): Int = {
    var index = 0
    var found = false
    while (!found && hasNext) if (elem == next()) { found = true } else { index += 1 }
    if (found) index else -1
  }

  def toByteString: ByteString

  override def toSeq: ByteString = toByteString

  @inline final override def foreach[@specialized U](f: Byte ⇒ U): Unit =
    while (hasNext) f(next())

  final override def foldLeft[@specialized B](z: B)(op: (B, Byte) ⇒ B): B = {
    var acc = z
    while (hasNext) acc = op(acc, next())
    acc
  }

  final override def toArray[B >: Byte](implicit arg0: ClassManifest[B]): Array[B] = {
    val target = Array.ofDim[B](len)
    copyToArray(target)
    target
  }

  /**
   * Copy as many bytes as possible to a ByteBuffer, starting from it's
   * current position. This method will not overflow the buffer.
   *
   * @param buffer a ByteBuffer to copy bytes to
   * @return the number of bytes actually copied
   */
  def copyToBuffer(buffer: ByteBuffer): Int
}

object ByteArrayIterator {
  private val emptyArray = Array.ofDim[Byte](0)

  protected[akka] def apply(array: Array[Byte]): ByteArrayIterator =
    new ByteArrayIterator(array, 0, array.length)

  protected[akka] def apply(array: Array[Byte], from: Int, until: Int): ByteArrayIterator =
    new ByteArrayIterator(array, from, until)

  val empty: ByteArrayIterator = apply(Array.empty[Byte])
}

class ByteArrayIterator private (private var array: Array[Byte], private var from: Int, private var until: Int) extends ByteIterator {
  protected[util] final def internalArray = array
  protected[util] final def internalFrom = from
  protected[util] final def internalUntil = until

  final def isIdenticalTo(that: Iterator[Byte]) = that match {
    case that: ByteArrayIterator ⇒
      ((this.array) eq (that.internalArray)) &&
        ((this.from) == (that.from)) && ((this.until) == (that.until))
    case _ ⇒ false
  }

  @inline final def len = until - from

  @inline final def hasNext = from < until

  @inline final def head = array(from)

  final def next() = {
    if (!hasNext) Iterator.empty.next
    else { val i = from; from = from + 1; array(i) }
  }

  def clear() { this.array = ByteArrayIterator.emptyArray; from = 0; until = from }

  final override def length = { val l = len; clear(); l }

  final override def ++(that: TraversableOnce[Byte]) = that match {
    case that: ByteArrayIterator ⇒ {
      if (this.isEmpty) that
      else if ((this.array eq that.array) && (this.until == that.from)) {
        this.until = that.until
        that.clear()
        this
      } else {
        val result = MultiByteArrayIterator(List(this, that))
        this.clear()
        result
      }
    }
    case that: MultiByteArrayIterator ⇒ if (this.isEmpty) that else (this +: that)
    case _                            ⇒ super.++(that)
  }

  final override def clone = new ByteArrayIterator(array, from, until)

  final override def take(n: Int) = {
    until = until min (from + (0 max n))
    this
  }

  final override def drop(n: Int) = {
    from = until min (from + (0 max n))
    this
  }

  final override def takeWhile(p: Byte ⇒ Boolean) = {
    val prev = from
    dropWhile(p)
    until = from; from = prev
    this
  }

  final override def dropWhile(p: Byte ⇒ Boolean) = {
    var stop = false
    while (!stop && hasNext) {
      if (p(array(from))) { from = from + 1 } else { stop = true }
    }
    this
  }

  final override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = {
    val n = 0 max ((xs.length - start) min this.len min len)
    Array.copy(this.array, from, xs, start, n)
    this.drop(n)
  }

  final override def toByteString = {
    val result =
      if ((from == 0) && (until == array.length)) ByteString.ByteString1C(array)
      else ByteString.ByteString1(array, from, len)
    clear()
    result
  }

  def copyToBuffer(buffer: ByteBuffer): Int = {
    val copyLength = math.min(buffer.remaining, len)
    if (copyLength > 0) {
      buffer.put(array, from, copyLength)
      drop(copyLength)
    }
    copyLength
  }
}

object MultiByteArrayIterator {
  protected val clearedList = List(ByteArrayIterator.empty)

  val empty = new MultiByteArrayIterator(Nil)

  protected[akka] def apply(iterators: List[ByteArrayIterator]): MultiByteArrayIterator =
    new MultiByteArrayIterator(iterators)
}

class MultiByteArrayIterator private (private var iterators: List[ByteArrayIterator]) extends ByteIterator {
  // After normalization:
  // * iterators.isEmpty == false
  // * (!iterator.head.isEmpty || iterators.tail.isEmpty) == true
  private def normalize(): this.type = {
    @tailrec def norm(xs: List[ByteArrayIterator]): List[ByteArrayIterator] = {
      if (xs.isEmpty) MultiByteArrayIterator.clearedList
      else if (xs.head.isEmpty) norm(xs.tail)
      else xs
    }
    iterators = norm(iterators)
    this
  }
  normalize()

  @inline private def current = iterators.head
  @inline private def dropCurrent() { iterators = iterators.tail }
  @inline def clear() { iterators = MultiByteArrayIterator.empty.iterators }

  final def isIdenticalTo(that: Iterator[Byte]) = false

  @inline final def hasNext = current.hasNext

  @inline final def head = current.head

  final def next() = {
    val result = current.next()
    normalize()
    result
  }

  final override def len = iterators.foldLeft(0) { _ + _.len }

  final override def length = {
    var result = len
    clear()
    result
  }

  def +:(that: ByteArrayIterator): this.type = {
    iterators = that +: iterators
    this
  }

  final override def ++(that: TraversableOnce[Byte]) = that match {
    case that: ByteArrayIterator ⇒ if (this.isEmpty) that else {
      iterators = iterators :+ that
      that.clear()
      this
    }
    case that: MultiByteArrayIterator ⇒ if (this.isEmpty) that else {
      iterators = this.iterators ++ that.iterators
      that.clear()
      this
    }
    case _ ⇒ super.++(that)
  }

  final override def clone = new MultiByteArrayIterator(iterators map { _.clone })

  final override def take(n: Int) = {
    var rest = n
    val builder = new ListBuffer[ByteArrayIterator]
    while ((rest > 0) && !iterators.isEmpty) {
      current.take(rest)
      if (current.hasNext) {
        rest -= current.len
        builder += current
      }
      iterators = iterators.tail
    }
    iterators = builder.result
    normalize()
  }

  @tailrec final override def drop(n: Int) = if ((n > 0) && !isEmpty) {
    val nCurrent = math.min(n, current.len)
    current.drop(n)
    val rest = n - nCurrent
    assert(current.isEmpty || (rest == 0))
    normalize()
    drop(rest)
  } else this

  final override def takeWhile(p: Byte ⇒ Boolean) = {
    var stop = false
    var builder = new ListBuffer[ByteArrayIterator]
    while (!stop && !iterators.isEmpty) {
      val lastLen = current.len
      current.takeWhile(p)
      if (current.hasNext) builder += current
      if (current.len < lastLen) stop = true
      dropCurrent()
    }
    iterators = builder.result
    normalize()
  }

  @tailrec final override def dropWhile(p: Byte ⇒ Boolean) = if (!isEmpty) {
    current.dropWhile(p)
    val dropMore = current.isEmpty
    normalize()
    if (dropMore) dropWhile(p) else this
  } else this

  final override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = {
    var pos = start
    var rest = len
    while ((rest > 0) && !iterators.isEmpty) {
      val n = 0 max ((xs.length - pos) min current.len min rest)
      current.copyToArray(xs, pos, n)
      pos += n
      rest -= n
      dropCurrent()
    }
    normalize()
  }

  final override def toByteString = {
    val result = iterators.foldLeft(ByteString.empty) { _ ++ _.toByteString }
    clear()
    result
  }

  def copyToBuffer(buffer: ByteBuffer): Int = {
    val n = iterators.foldLeft(0) { _ + _.copyToBuffer(buffer) }
    normalize()
    n
  }
}
