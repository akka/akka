package akka.streams

import scala.annotation.tailrec
import ResizableMultiReaderRingBuffer._

/**
 * A mutable RingBuffer that can grow in size and supports multiple readers.
 * Contrary to many other ring buffer implementations this one does not automatically overwrite the oldest
 * elements, rather, if full, the buffer tries to grow and rejects further writes if max capacity is reached.
 */
class ResizableMultiReaderRingBuffer[T >: Null <: AnyRef](val initialSize: Int,
                                                          val maxSize: Int,
                                                          val cursors: Cursors) {
  require(maxSize <= Int.MaxValue / 2, "maxSize must be <= Int.MaxValue / 2")
  require(0 < initialSize && initialSize <= maxSize, "initialSize must be > 0 and <= maxSize")

  private[this] var array = new Array[AnyRef](initialSize)

  // Usual ring buffer implementations keep two pointers into the array which are wrapped around
  // (mod array.length) upon increase. However, there is an ambiguity when writeIx == readIx since this state
  // will be reached when the buffer is completely empty as well as when the buffer is completely full.
  // An easy fix is to add another field (like 'count') that serves as additional data point resolving the
  // ambiguity. However, adding another (esp. volatile) field adds more overhead than required and is
  // especially inconvenient when supporting multiple readers.
  // Therefore the approach we take here does not add another field, rather we don't wrap around the pointers
  // at all but simply rebase them from time to time when we have to loop through the cursors anyway.
  private[this] var writeIx = 0
  private[this] var readIx = 0 // the "oldest" of all read cursor indices, i.e. the one that is most behind

  /**
   * The number of elements currently in the buffer.
   */
  def size: Int = writeIx - readIx

  /**
   * The number of elements the buffer can still take without having to be resized.
   */
  def immediatelyAvailable: Int = array.length - size

  /**
   * The maximum number of elements the buffer can still take.
   */
  def maxAvailable: Int = maxSize - size

  /**
   * Applies availability bounds to the given element number.
   * Equal to `min(maxAvailable, max(immediatelyAvailable, elements))` but slightly more efficient.
   */
  def potentiallyAvailable(elements: Int): Int = {
    val size = this.size
    math.min(maxSize - size, math.max(array.length - size, elements))
  }

  /**
   * Returns the number of elements that the buffer currently contains for the given cursor.
   */
  def count(cursor: Cursor): Int = writeIx - cursor.cursor

  /**
   * Tries to write the given value into the buffer thereby potentially growing the backing array.
   * Returns `true` if the write was successful and false if the buffer is full and cannot grow anymore.
   */
  def write(value: T): Boolean = {
    val len = array.length
    if (writeIx - readIx < len) { // if we have space left we can simply write and be done
      array(writeIx % len) = value
      this.writeIx = writeIx + 1
      true
    } else if (len < maxSize) { // if we are full but can grow we do so
      // the growing logic is quite simple: we assemble all current buffer entries in the new array
      // in their natural order (removing potential wrap around) and rebase all indices to zero
      val newLen = math.min(len << 1, maxSize)
      val newArray = new Array[AnyRef](newLen)
      val r = readIx % len
      System.arraycopy(array, r, newArray, 0, len - r)
      System.arraycopy(array, 0, newArray, len - r, r)
      @tailrec def rebaseCursors(remaining: List[Cursor]): Unit = remaining match {
        case head :: tail ⇒
          head.cursor -= readIx
          rebaseCursors(tail)
        case _ ⇒ // done
      }
      rebaseCursors(cursors.cursors)
      val w = writeIx - readIx
      newArray(w % newLen) = value
      array = newArray
      writeIx = w + 1
      readIx = 0
      true
    } else false
  }

  /**
   * Tries to read from the buffer using the given Cursor.
   * If there are no more data to be read (i.e. the cursor is already at writeIx) the method returns null !
   */
  def read(cursor: Cursor): T = {
    val c = cursor.cursor
    if (c < writeIx) {
      if (c == readIx) {
        val threshold = rebaseThreshold
        if (c > threshold) {
          @tailrec def rebaseCursorsAndDetermineNoOtherAtReadIx(remaining: List[Cursor], result: Boolean = true): Boolean =
            remaining match {
              case head :: tail ⇒
                val next =
                  if (head eq cursor) {
                    head.cursor = c - threshold + 1
                    result
                  } else {
                    val hc = head.cursor
                    head.cursor = hc - threshold
                    result && hc != c
                  }
                rebaseCursorsAndDetermineNoOtherAtReadIx(tail, next)
              case _ ⇒ result
            }
          this.writeIx = writeIx - threshold
          readIx = if (rebaseCursorsAndDetermineNoOtherAtReadIx(cursors.cursors)) c - threshold + 1 else c - threshold
        } else {
          @tailrec def noOtherAtReadIx(remaining: List[Cursor]): Boolean = remaining match {
            case head :: tail ⇒ ((head eq cursor) || head.cursor != c) && noOtherAtReadIx(tail)
            case _            ⇒ true
          }
          val newC = c + 1
          if (noOtherAtReadIx(cursors.cursors)) readIx = newC
          cursor.cursor = newC
        }
      } else cursor.cursor = c + 1
      array(c % array.length).asInstanceOf[T]
    } else null
  }

  // from time to time we need to rebase all pointers and cursors so they don't overflow,
  // rebasing is performed when `readIx` is greater than this threshold which *must* be a multiple of array.length!
  protected def rebaseThreshold: Int = {
    val x = Int.MaxValue / 2
    x - x % array.length // the largest safe threshold is the largest multiple of array.length that is < Int.MaxValue / 2
  }

  protected def arrayLen: Int = array.length

  /**
   * Initializes the given Cursor to the oldest buffer entry that is still available.
   */
  def initCursor(cursor: Cursor): Unit = cursor.cursor = readIx

  def toSeq: Seq[AnyRef] = array.toSeq

  override def toString: String =
    s"ResizableMultiReaderRingBuffer(size=$size, writeIx=$writeIx, readIx=$readIx, cursors=${cursors.cursors.size})"
}

object ResizableMultiReaderRingBuffer {
  trait Cursors {
    def cursors: List[Cursor]
  }
  trait Cursor {
    def cursor: Int
    def cursor_=(ix: Int): Unit
  }
}