/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import java.nio.charset.Charset
import java.io.{ File, FileInputStream }
import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

/**
 *  An abstraction of bytes either from a JVM heap buffer (ByteString), or a slice
 *  of a file on the file-system, or a combination of both.
 *
 *  It provides operations to combine and slice bytes efficiently and usually without
 *  having to load bytes from files into memory or by copying data around. Operations
 *  that actually load bytes from files are marked as such.
 *
 *  Bytes instances are produced by using the given constructors in the companion object and
 *  by combining/slicing the results with the instance methods.
 *
 *  Bytes instances are usually consumed by pattern-matching over its structure. This way
 *  bytes from files can be read in the most efficient way possible for the given purpose
 *  (e.g. by using `FileChannel.transferTo` where applicable).
 *
 *  The complete sealed type hierarchy looks like this:
 *
 *  Bytes
 *  |-- SimpleBytes
 *  |   |-- ByteStringBytes (= ByteString)
 *  |   |-- FileBytes
 *  |-- CompoundBytes
 */
sealed abstract class Bytes {
  def isEmpty: Boolean = longLength == 0L
  def nonEmpty: Boolean = !isEmpty

  /**
   * Determines whether this instance is or contains data that are not
   * already present in the JVM heap (i.e. instance of Bytes.FileBytes).
   */
  def hasFileBytes: Boolean

  /**
   * Returns the number of bytes contained in this instance.
   */
  def longLength: Long

  /**
   * Extracts `span` bytes from this instance starting at `sourceOffset` and copies
   * them to the `xs` starting at `targetOffset`. If `span` is larger than the number
   * of bytes available in this instance after the `sourceOffset` or if `xs` has
   * less space available after `targetOffset` the number of bytes copied is
   * decreased accordingly (i.e. it is not an error to specify a `span` that is
   * too large).
   */
  def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = longLength.toInt): Unit

  /**
   * Returns a slice of this instance's content as a `ByteString`.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   */
  def sliceBytes(offset: Long = 0, span: Int = longLength.toInt): ByteString

  /** Returns a slice of this instance as a `Bytes`. */
  def slice(offset: Long = 0, span: Long = longLength): Bytes

  /**
   * Copies the contents of this instance into a new byte array.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def toByteArray: Array[Byte]

  /**
   * Same as `toByteArray` but returning a `ByteString` instead.
   * More efficient if this instance is a `Bytes` instance since no data will have
   * to be copied and the `ByteString` will not have to be newly created.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def toByteString: ByteString

  /**
   * Returns the contents of this instance as a `Stream[Bytes]` with each
   * chunk not being larger than the given `maxChunkSize`.
   */
  def toChunkStream(maxChunkSize: Long): Stream[Bytes]

  /** Appends Bytes */
  def ++(other: Bytes): Bytes

  /**
   * Returns the contents of this instance as a string (using UTF-8 encoding).
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def asString: String = asString(Charset.forName("utf8"))

  /**
   * Returns the contents of this instance as a string.
   *
   * CAUTION: Since this instance might point to bytes contained in an off-memory file
   * this method might cause the loading of a large amount of data into the JVM
   * heap (up to 2 GB!).
   * If this instance is a `FileBytes` instance containing more than 2GB of data
   * the method will throw an `IllegalArgumentException`.
   */
  def asString(charset: Charset): String = new String(toByteArray, charset)
}

object Bytes {
  private val utf8 = Charset.forName("utf8")
  def apply(string: String): Bytes = apply(string, utf8)
  def apply(string: String, charset: Charset): Bytes =
    ByteString.ByteString1C(string getBytes charset)
  def apply(bytes: Array[Byte]): Bytes = ByteString(bytes)

  /**
   * Creates a [[FileBytes]] instance if the given file exists, is readable,
   * non-empty and the given `length` parameter is non-zero. Otherwise the method returns
   * an empty [[ByteString]].
   * A negative `length` value signifies that the respective number of bytes at the end of the
   * file is to be omitted, i.e., a value of -10 will select all bytes starting at `offset`
   * except for the last 10.
   * If `length` is greater or equal to "file length - offset" all bytes in the file starting at
   * `offset` are selected.
   */
  def apply(file: File, offset: Long = 0, length: Long = Long.MaxValue): Bytes = {
    val fileLength = file.length
    if (fileLength > 0) {
      require(offset >= 0 && offset < fileLength, s"offset $offset out of range $fileLength")
      if (file.canRead)
        if (length > 0) new FileBytes(file.getAbsolutePath, offset, math.min(fileLength - offset, length))
        else if (length < 0 && length > offset - fileLength) new FileBytes(file.getAbsolutePath, offset, fileLength - offset + length)
        else Empty
      else Empty
    } else Empty
  }

  /**
   * Creates a [[FileBytes]] instance if the given file exists, is readable,
   * non-empty and the given `length` parameter is non-zero. Otherwise the method returns an
   * empty [[ByteString]].
   * A negative `length` value signifies that the respective number of bytes at the end of the
   * file is to be omitted, i.e., a value of -10 will select all bytes starting at `offset`
   * except for the last 10.
   * If `length` is greater or equal to "file length - offset" all bytes in the file starting at
   * `offset` are selected.
   */
  def fromFile(fileName: String, offset: Long = 0, length: Long = Long.MaxValue) =
    apply(new File(fileName), offset, length)

  val Empty: Bytes = ByteString.empty

  /** SimpleBytes are either completely heap-based or completely file-based but no combination */
  sealed abstract class SimpleBytes private[util] extends Bytes {
    def toByteArray = {
      require(longLength <= Int.MaxValue, "Cannot create a byte array greater than 2GB")
      val array = new Array[Byte](longLength.toInt)
      copyToArray(array)
      array
    }
    def sliceBytes(offset: Long, span: Int): ByteString = slice(offset, span).toByteString

    def toChunkStream(maxChunkSize: Long): Stream[Bytes] = {
      require(maxChunkSize > 0, "chunkSize must be > 0")
      val lastChunkStart = longLength - maxChunkSize
      def nextChunk(ix: Long = 0): Stream[Bytes] = {
        if (ix < lastChunkStart) Stream.cons(slice(ix, maxChunkSize), nextChunk(ix + maxChunkSize))
        else Stream.cons(slice(ix, longLength - ix), Stream.Empty)
      }
      nextChunk()
    }
  }
  /**
   * A sentinel middle class to use in pattern matches instead of `ByteString` to complete the
   * sealed type hierarchy of Bytes. Any other possible implementation of ByteStringBytes must behave
   * like ByteString (and is discouraged by making its constructor private[akka.util]).
   */
  abstract class ByteStringBytes private[util] extends SimpleBytes {
    def length: Int
    def iterator: ByteIterator

    def ++(other: Bytes): Bytes =
      if (isEmpty) other
      else other match {
        case ByteString.empty                                ⇒ this
        case bs: ByteString                                  ⇒ this.toByteString ++ bs
        case Bytes.CompoundBytes((head: ByteString) +: tail) ⇒ Bytes.CompoundBytes((this.toByteString ++ head) +: tail)
        case Bytes.CompoundBytes(parts)                      ⇒ Bytes.CompoundBytes(this.toByteString +: parts)
        case o: Bytes.SimpleBytes                            ⇒ Bytes.CompoundBytes(Vector(this.toByteString, o))
      }

    def hasFileBytes: Boolean = false
    def longLength: Long = length

    def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = length.toInt) = {
      require(sourceOffset >= 0, "sourceOffset must be >= 0 but is " + sourceOffset)
      if (sourceOffset < length)
        iterator.drop(sourceOffset.toInt).copyToArray(xs, targetOffset, span)
    }
    def slice(offset: Long, span: Long): Bytes = {
      require(offset >= 0, "offset must be >= 0")
      require(span >= 0, "span must be >= 0")

      if (offset < length && span > 0)
        if (offset > 0 || span < length) toByteString.slice(offset.toInt, math.min(offset + span, Int.MaxValue).toInt)
        else this
      else Bytes.Empty
    }
  }
  /** A reference to Bytes coming from a File */
  case class FileBytes private[util] (fileName: String, offset: Long = 0, length: Long) extends SimpleBytes {
    def ++(other: Bytes): Bytes = other match {
      case ByteString.empty                                    ⇒ this
      case FileBytes(`fileName`, o, l) if o == offset + length ⇒ copy(length = length + l)
      case CompoundBytes(parts)                                ⇒ CompoundBytes(this +: parts)
      case o: SimpleBytes                                      ⇒ CompoundBytes(Vector(this, o))
    }

    def longLength = length
    def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = longLength.toInt) = {
      require(sourceOffset >= 0, "sourceOffset must be >= 0 but is " + sourceOffset)
      if (span > 0 && xs.length > 0 && sourceOffset < longLength) {
        require(0 <= targetOffset && targetOffset < xs.length, s"start must be >= 0 and <= ${xs.length} but is $targetOffset")
        val input = new FileInputStream(fileName)
        try {
          input.skip(offset + sourceOffset)
          val targetEnd = math.min(xs.length, targetOffset + math.min(span, (length - sourceOffset).toInt))
          @tailrec def load(ix: Int = targetOffset): Unit =
            if (ix < targetEnd)
              input.read(xs, ix, targetEnd - ix) match {
                case -1 ⇒ // file length changed since this FileBytes instance was created
                  java.util.Arrays.fill(xs, ix, targetEnd, 0.toByte) // zero out remaining space
                case count ⇒ load(ix + count)
              }
          load()
        } finally input.close()
      }
    }
    def toByteString = ByteString.ByteString1C(toByteArray)
    def slice(offset: Long, span: Long): Bytes = {
      require(offset >= 0, "offset must be >= 0")
      require(span >= 0, "span must be >= 0")

      if (offset < longLength && span > 0) {
        val newOffset = this.offset + offset
        val newLength = math.min(longLength - offset, span)
        FileBytes(fileName, newOffset, newLength)
      } else Bytes.Empty
    }
    def hasFileBytes: Boolean = true
  }
  /** A combination of heap-based and file-based Bytes */
  case class CompoundBytes private[util] (parts: Vector[SimpleBytes]) extends Bytes {
    require(parts.nonEmpty)
    def sliceBytes(offset: Long, span: Int): ByteString = slice(offset, span).toByteString
    def toByteArray: Array[Byte] = {
      require(longLength <= Int.MaxValue, "Cannot create a byte array greater than 2GB")
      val result = new Array[Byte](longLength.toInt)
      copyToArray(result)
      result
    }
    def ++(other: Bytes): Bytes = other match {
      case ByteString.empty    ⇒ this
      case CompoundBytes(more) ⇒ CompoundBytes(parts ++ more)
      case c: SimpleBytes      ⇒ CompoundBytes(parts :+ c)
    }

    lazy val hasFileBytes = parts.exists(_.hasFileBytes)
    lazy val longLength = parts.map(_.longLength).sum
    def iterator: Iterator[SimpleBytes] = parts.iterator
    def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = longLength.toInt): Unit = {
      require(sourceOffset >= 0, "sourceOffset must be >= 0 but is " + sourceOffset)
      if (span > 0 && xs.length > 0 && sourceOffset < longLength) {
        require(0 <= targetOffset && targetOffset < xs.length, s"start must be >= 0 and <= ${xs.length} but is $targetOffset")
        val targetEnd: Int = math.min(xs.length, targetOffset + math.min(span, (longLength - sourceOffset).toInt))
        val iter = iterator
        @tailrec def rec(sourceOffset: Long = sourceOffset, targetOffset: Int = targetOffset): Unit =
          if (targetOffset < targetEnd && iter.hasNext) {
            val current = iter.next()
            if (sourceOffset < current.longLength) {
              current.copyToArray(xs, sourceOffset, targetOffset, span = targetEnd - targetOffset)
              rec(0, math.min(targetOffset + current.longLength - sourceOffset, Int.MaxValue).toInt)
            } else rec(sourceOffset - current.longLength, targetOffset)
          }
        rec()
      }
    }
    def slice(offset: Long, span: Long): Bytes = {
      require(offset >= 0, "offset must be >= 0")
      require(span >= 0, "span must be >= 0")
      if (offset < longLength && span > 0) {
        val iter = iterator
        val builder = Bytes.newBuilder
        @tailrec def rec(offset: Long = offset, span: Long = span): Bytes =
          if (span > 0 && iter.hasNext) {
            val current = iter.next()
            if (offset < current.longLength) {
              val piece = current.slice(offset, span)
              if (piece.nonEmpty) builder += piece
              rec(0, math.max(0, span - piece.longLength))
            } else rec(offset - current.longLength, span)
          } else builder.result()
        rec()
      } else Bytes.Empty
    }
    def toByteString = {
      require(longLength <= Int.MaxValue, "Cannot create a ByteString greater than 2GB")
      parts.foldLeft(ByteString.empty)(_ ++ _.toByteString)
    }

    // overridden to run lazily
    override def toChunkStream(maxChunkSize: Long): Stream[Bytes] =
      Stream.cons(slice(0, maxChunkSize), slice(maxChunkSize).toChunkStream(maxChunkSize))

    override def toString = parts.map(_.toString).mkString(" ++ ")
  }

  def newBuilder: Builder = new Builder

  class Builder extends scala.collection.mutable.Builder[Bytes, Bytes] {
    private val b = new VectorBuilder[SimpleBytes]
    private var _byteCount = 0L

    def byteCount: Long = _byteCount

    def +=(x: SimpleBytes): this.type = {
      b += x
      _byteCount += x.longLength
      this
    }

    def +=(elem: Bytes): this.type =
      elem match {
        case Empty          ⇒ this
        case x: SimpleBytes ⇒ this += x
        case CompoundBytes(parts) ⇒
          // TODO: optimize?
          parts.foreach(this += _); this
      }

    def clear(): Unit = b.clear()

    def result(): Bytes = {
      val res = b.result()
      res.size match {
        case 0 ⇒ ByteString.empty
        case 1 ⇒ res.head
        case _ ⇒ CompoundBytes(res)
      }
    }
  }
}
