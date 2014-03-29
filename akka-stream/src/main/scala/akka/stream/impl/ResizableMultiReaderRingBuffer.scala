/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.util.control.NoStackTrace
import ResizableMultiReaderRingBuffer._

/**
 * INTERNAL API
 * A mutable RingBuffer that can grow in size and supports multiple readers.
 * Contrary to many other ring buffer implementations this one does not automatically overwrite the oldest
 * elements, rather, if full, the buffer tries to grow and rejects further writes if max capacity is reached.
 */
private[akka] class ResizableMultiReaderRingBuffer[T](initialSize: Int, // constructor param, not field
                                                      maxSize: Int, // constructor param, not field
                                                      val cursors: Cursors) {
  require(Integer.lowestOneBit(maxSize) == maxSize && 0 < maxSize && maxSize <= Int.MaxValue / 2,
    "maxSize must be a power of 2 that is > 0 and < Int.MaxValue/2")
  require(Integer.lowestOneBit(initialSize) == initialSize && 0 < initialSize && initialSize <= maxSize,
    "initialSize must be a power of 2 that is > 0 and <= maxSize")

  private[this] val maxSizeBit = Integer.numberOfTrailingZeros(maxSize)
  private[this] var array = new Array[Any](initialSize)

  // Usual ring buffer implementations keep two pointers into the array which are wrapped around
  // (mod array.length) upon increase. However, there is an ambiguity when writeIx == readIx since this state
  // will be reached when the buffer is completely empty as well as when the buffer is completely full.
  // An easy fix is to add another field (like 'count') that serves as additional data point resolving the
  // ambiguity. However, adding another field adds more overhead than required and is especially inconvenient
  // when supporting multiple readers.
  // Therefore the approach we take here does not add another field, rather we don't wrap around the pointers
  // at all but simply rebase them from time to time when we have to loop through the cursors anyway.
  private[this] var writeIx = 0
  private[this] var readIx = 0 // the "oldest" of all read cursor indices, i.e. the one that is most behind

  // current array.length log2, we don't keep it as an extra field because `Integer.numberOfTrailingZeros`
  // is a JVM intrinsic compiling down to a `BSF` instruction on x86, which is very fast on modern CPUs
  private def lenBit: Int = Integer.numberOfTrailingZeros(array.length)

  // bit mask for converting a cursor into an array index
  private def mask: Int = Int.MaxValue >> (31 - lenBit)

  /**
   * The number of elements currently in the buffer.
   */
  def size: Int = writeIx - readIx

  def isEmpty: Boolean = size == 0

  def nonEmpty: Boolean = !isEmpty

  /**
   * The number of elements the buffer can still take without having to be resized.
   */
  def immediatelyAvailable: Int = array.length - size

  /**
   * The maximum number of elements the buffer can still take.
   */
  def maxAvailable: Int = (1 << maxSizeBit) - size

  /**
   * Applies availability bounds to the given element number.
   * Equivalent to `min(maxAvailable, max(immediatelyAvailable, elements))`.
   */
  // FIXME this is nonsense (always returns maxAvailable)
  def potentiallyAvailable(elements: Int): Int =
    math.min(maxAvailable, math.max(immediatelyAvailable, elements))

  /**
   * Returns the number of elements that the buffer currently contains for the given cursor.
   */
  def count(cursor: Cursor): Int = writeIx - cursor.cursor

  /**
   * Initializes the given Cursor to the oldest buffer entry that is still available.
   */
  def initCursor(cursor: Cursor): Unit = cursor.cursor = readIx

  /**
   * Tries to write the given value into the buffer thereby potentially growing the backing array.
   * Returns `true` if the write was successful and false if the buffer is full and cannot grow anymore.
   */
  def write(value: T): Boolean =
    if (size < array.length) { // if we have space left we can simply write and be done
      array(writeIx & mask) = value
      writeIx = writeIx + 1
      true
    } else if (lenBit < maxSizeBit) { // if we are full but can grow we do so
      // the growing logic is quite simple: we assemble all current buffer entries in the new array
      // in their natural order (removing potential wrap around) and rebase all indices to zero
      val r = readIx & mask
      val newArray = new Array[Any](array.length << 1)
      System.arraycopy(array, r, newArray, 0, array.length - r)
      System.arraycopy(array, 0, newArray, array.length - r, r)
      @tailrec def rebaseCursors(remaining: List[Cursor]): Unit = remaining match {
        case head :: tail ⇒
          head.cursor -= readIx
          rebaseCursors(tail)
        case _ ⇒ // done
      }
      rebaseCursors(cursors.cursors)
      array = newArray
      val w = size
      array(w & mask) = value
      writeIx = w + 1
      readIx = 0
      true
    } else false

  /**
   * Tries to read from the buffer using the given Cursor.
   * If there are no more data to be read (i.e. the cursor is already
   * at writeIx) the method throws ResizableMultiReaderRingBuffer.NothingToReadException!
   */
  def read(cursor: Cursor): T = {
    val c = cursor.cursor
    if (c < writeIx) {
      cursor.cursor += 1
      if (c == readIx) updateReadIxAndPotentiallyRebaseCursors()
      array(c & mask).asInstanceOf[T]
    } else throw NothingToReadException
  }

  def onCursorRemoved(cursor: Cursor): Unit =
    if (cursor.cursor == readIx) // if this cursor is the last one it must be at readIx
      updateReadIxAndPotentiallyRebaseCursors()

  private def updateReadIxAndPotentiallyRebaseCursors(): Unit = {
    val threshold = rebaseThreshold
    if (readIx > threshold) {
      @tailrec def rebaseCursorsAndReturnMin(remaining: List[Cursor], result: Int): Int =
        remaining match {
          case head :: tail ⇒
            head.cursor -= threshold
            rebaseCursorsAndReturnMin(tail, math.min(head.cursor, result))
          case _ ⇒ result
        }
      writeIx -= threshold
      readIx = rebaseCursorsAndReturnMin(cursors.cursors, writeIx)
    } else {
      @tailrec def minCursor(remaining: List[Cursor], result: Int): Int =
        remaining match {
          case head :: tail ⇒ minCursor(tail, math.min(head.cursor, result))
          case _            ⇒ result
        }
      readIx = minCursor(cursors.cursors, writeIx)
      // TODO: is not nulling-out the now unused buffer cells acceptable here?
    }
  }

  // from time to time we need to rebase all pointers and cursors so they don't overflow,
  // rebasing is performed when `readIx` is greater than this threshold which *must* be a multiple of array.length!
  // default: the largest safe threshold which is the largest multiple of array.length that is < Int.MaxValue/2
  protected def rebaseThreshold: Int = (Int.MaxValue / 2) & ~mask

  protected def underlyingArray: Array[Any] = array

  override def toString: String =
    s"ResizableMultiReaderRingBuffer(size=$size, writeIx=$writeIx, readIx=$readIx, cursors=${cursors.cursors.size})"
}

/**
 * INTERNAL API
 */
private[akka] object ResizableMultiReaderRingBuffer {
  object NothingToReadException extends RuntimeException with NoStackTrace

  trait Cursors {
    def cursors: List[Cursor]
  }
  trait Cursor {
    def cursor: Int
    def cursor_=(ix: Int): Unit
  }
}