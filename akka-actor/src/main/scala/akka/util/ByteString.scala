/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.nio.ByteBuffer

import scala.collection.IndexedSeqOptimized
import scala.collection.mutable.{ Builder, WrappedArray }
import scala.collection.immutable.{ IndexedSeq, VectorBuilder }
import scala.collection.generic.CanBuildFrom

object ByteString {

  /**
   * Creates a new ByteString by copying a byte array.
   */
  def apply(bytes: Array[Byte]): ByteString = CompactByteString(bytes)

  /**
   * Creates a new ByteString by copying bytes.
   */
  def apply(bytes: Byte*): ByteString = CompactByteString(bytes: _*)

  /**
   * Creates a new ByteString by converting from integral numbers to bytes.
   */
  def apply[T](bytes: T*)(implicit num: Integral[T]): ByteString =
    CompactByteString(bytes: _*)(num)

  /**
   * Creates a new ByteString by copying bytes from a ByteBuffer.
   */
  def apply(bytes: ByteBuffer): ByteString = CompactByteString(bytes)

  /**
   * Creates a new ByteString by encoding a String as UTF-8.
   */
  def apply(string: String): ByteString = apply(string, "UTF-8")

  /**
   * Creates a new ByteString by encoding a String with a charset.
   */
  def apply(string: String, charset: String): ByteString = CompactByteString(string, charset)

  /**
   * Creates a new ByteString by copying length bytes starting at offset from
   * an Array.
   */
  def fromArray(array: Array[Byte], offset: Int, length: Int): ByteString =
    CompactByteString.fromArray(array, offset, length)

  val empty: ByteString = CompactByteString(Array.empty[Byte])

  def newBuilder = new ByteStringBuilder

  implicit def canBuildFrom = new CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] {
    def apply(from: TraversableOnce[Byte]) = newBuilder
    def apply() = newBuilder
  }

  private[akka] object ByteString1C {
    def apply(bytes: Array[Byte]) = new ByteString1C(bytes)
  }

  /**
   * A compact (unsliced) and unfragmented ByteString, implementaton of ByteString1C.
   */
  @SerialVersionUID(3956956327691936932L)
  final class ByteString1C private (private val bytes: Array[Byte]) extends CompactByteString {
    def apply(idx: Int): Byte = bytes(idx)

    override def length = bytes.length

    override def iterator = ByteArrayIterator(bytes, 0, bytes.length)

    def toByteString1: ByteString1 = ByteString1(bytes)

    override def clone: ByteString1C = new ByteString1C(toArray)

    def asByteBuffer: ByteBuffer =
      toByteString1.asByteBuffer

    def decodeString(charset: String): String = new String(bytes, charset)

    def ++(that: ByteString): ByteString =
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else toByteString1 ++ that

    override def slice(from: Int, until: Int): ByteString =
      if ((from != 0) || (until != length)) toByteString1.slice(from, until)
      else this
  }

  private[akka] object ByteString1 {
    def apply(bytes: Array[Byte]) = new ByteString1(bytes)
    def apply(bytes: Array[Byte], startIndex: Int, length: Int) = new ByteString1(bytes, startIndex, length)
  }

  /**
   * An unfragmented ByteString.
   */
  final class ByteString1 private (private val bytes: Array[Byte], private val startIndex: Int, val length: Int) extends ContByteString {

    private def this(bytes: Array[Byte]) = this(bytes, 0, bytes.length)

    def apply(idx: Int): Byte = bytes(checkRangeConvert(idx))

    override def iterator = ByteArrayIterator(bytes, startIndex, startIndex + length)

    private def checkRangeConvert(index: Int) = {
      if (0 <= index && length > index)
        index + startIndex
      else
        throw new IndexOutOfBoundsException(index.toString)
    }

    override def clone: CompactByteString = ByteString1C(toArray)

    def compact: CompactByteString =
      if (length == bytes.length) ByteString1C(bytes) else clone

    def asByteBuffer: ByteBuffer = {
      val buffer = ByteBuffer.wrap(bytes, startIndex, length).asReadOnlyBuffer
      if (buffer.remaining < bytes.length) buffer.slice
      else buffer
    }

    def decodeString(charset: String): String =
      new String(if (length == bytes.length) bytes else toArray, charset)

    def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else that match {
        case b: ByteString1C ⇒ ByteStrings(this, b.toByteString1)
        case b: ByteString1 ⇒ {
          if ((bytes eq b.bytes) && (startIndex + length == b.startIndex))
            new ByteString1(bytes, startIndex, length + b.length)
          else ByteStrings(this, b)
        }
        case bs: ByteStrings ⇒ ByteStrings(this, bs)
      }
    }
  }

  private[akka] object ByteStrings {
    def apply(bytestrings: Vector[ByteString1]): ByteString = new ByteStrings(bytestrings, (0 /: bytestrings)(_ + _.length))

    def apply(bytestrings: Vector[ByteString1], length: Int): ByteString = new ByteStrings(bytestrings, length)

    def apply(b1: ByteString1, b2: ByteString1): ByteString = compare(b1, b2) match {
      case 3 ⇒ new ByteStrings(Vector(b1, b2), b1.length + b2.length)
      case 2 ⇒ b2
      case 1 ⇒ b1
      case 0 ⇒ ByteString.empty
    }

    def apply(b: ByteString1, bs: ByteStrings): ByteString = compare(b, bs) match {
      case 3 ⇒ new ByteStrings(b +: bs.bytestrings, bs.length + b.length)
      case 2 ⇒ bs
      case 1 ⇒ b
      case 0 ⇒ ByteString.empty
    }

    def apply(bs: ByteStrings, b: ByteString1): ByteString = compare(bs, b) match {
      case 3 ⇒ new ByteStrings(bs.bytestrings :+ b, bs.length + b.length)
      case 2 ⇒ b
      case 1 ⇒ bs
      case 0 ⇒ ByteString.empty
    }

    def apply(bs1: ByteStrings, bs2: ByteStrings): ByteString = compare(bs1, bs2) match {
      case 3 ⇒ new ByteStrings(bs1.bytestrings ++ bs2.bytestrings, bs1.length + bs2.length)
      case 2 ⇒ bs2
      case 1 ⇒ bs1
      case 0 ⇒ ByteString.empty
    }

    // 0: both empty, 1: 2nd empty, 2: 1st empty, 3: neither empty
    def compare(b1: ByteString, b2: ByteString): Int =
      if (b1.length == 0)
        if (b2.length == 0) 0 else 2
      else if (b2.length == 0) 1 else 3

  }

  /**
   * A ByteString with 2 or more fragments.
   */
  final class ByteStrings private (val bytestrings: Vector[ByteString1], val length: Int) extends ByteString {

    def apply(idx: Int): Byte =
      if (0 <= idx && idx < length) {
        var pos = 0
        var seen = 0
        while (idx >= seen + bytestrings(pos).length) {
          seen += bytestrings(pos).length
          pos += 1
        }
        bytestrings(pos)(idx - seen)
      } else throw new IndexOutOfBoundsException(idx.toString)

    override def iterator = MultiByteArrayIterator(bytestrings.toList.map { _.iterator })

    def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else that match {
        case b: ByteString1C ⇒ ByteStrings(this, b.toByteString1)
        case b: ByteString1  ⇒ ByteStrings(this, b)
        case bs: ByteStrings ⇒ ByteStrings(this, bs)
      }
    }

    def contiguous = compact
    def compact: CompactByteString = {
      val ar = new Array[Byte](length)
      var pos = 0
      bytestrings foreach { b ⇒
        b.copyToArray(ar, pos, b.length)
        pos += b.length
      }
      ByteString1C(ar)
    }

    def asByteBuffer: ByteBuffer = compact.asByteBuffer

    def decodeString(charset: String): String = compact.decodeString(charset)
  }

}

/**
 * A [[http://en.wikipedia.org/wiki/Rope_(computer_science) Rope-like]] immutable
 * data structure containing bytes. The goal of this structure is to reduce
 * copying of arrays when concatenating and slicing sequences of bytes, and also
 * providing a thread safe way of working with bytes.
 *
 * TODO: Add performance characteristics
 */
sealed abstract class ByteString extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteString] {
  override protected[this] def newBuilder = ByteString.newBuilder

  // *must* be overridden by derived classes
  override def iterator: ByteIterator = null

  @inline final override def head: Byte = this(0)
  @inline final override def tail: ByteString = this.drop(1)
  @inline final override def last: Byte = this(this.length - 1)
  override def init: ByteString = this.take(this.length - 1)

  override def slice(from: Int, until: Int): ByteString =
    if ((from == 0) && (until == length)) this
    else iterator.slice(from, until).toByteString

  override def take(n: Int): ByteString = slice(0, n)
  override def takeRight(n: Int): ByteString = slice(length - n, length)
  override def drop(n: Int): ByteString = slice(n, length)
  override def dropRight(n: Int): ByteString = slice(0, length - n)

  override def takeWhile(p: Byte ⇒ Boolean) = iterator.takeWhile(p).toByteString
  override def dropWhile(p: Byte ⇒ Boolean) = iterator.dropWhile(p).toByteString
  override def span(p: Byte ⇒ Boolean): (ByteString, ByteString) =
    { val (a, b) = iterator.span(p); (a.toByteString, b.toByteString) }

  override def splitAt(n: Int): (ByteString, ByteString) = (take(n), drop(n))

  override def indexWhere(p: Byte ⇒ Boolean): Int = iterator.indexWhere(p)
  override def indexOf[B >: Byte](elem: B): Int = iterator.indexOf(elem)

  override def toArray[B >: Byte](implicit arg0: ClassManifest[B]) = iterator.toArray
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int) = iterator.copyToArray(xs, start, len)

  @inline final override def foreach[@specialized U](f: Byte ⇒ U): Unit = iterator foreach f

  /**
   * Efficiently concatenate another ByteString.
   */
  def ++(that: ByteString): ByteString

  /**
   * Copy as many bytes as possible to a ByteBuffer, starting from it's
   * current position. This method will not overflow the buffer.
   *
   * @param buffer a ByteBuffer to copy bytes to
   * @return the number of bytes actually copied
   */
  def copyToBuffer(buffer: ByteBuffer): Int = iterator.copyToBuffer(buffer)

  /**
   * Create a new ByteString with all contents contiguous in memory
   * (in a single section of a byte array).
   */
  def contiguous: ContByteString

  /**
   * Create a new ByteString with all contents compacted into a single,
   * full byte array.
   */
  def compact: CompactByteString

  /**
   * Returns a read-only ByteBuffer that directly wraps this ByteString
   * if it is not fragmented.
   */
  def asByteBuffer: ByteBuffer

  /**
   * Creates a new ByteBuffer with a copy of all bytes contained in this
   * ByteString.
   */
  final def toByteBuffer: ByteBuffer = ByteBuffer.wrap(toArray)

  /**
   * Decodes this ByteString as a UTF-8 encoded String.
   */
  final def utf8String: String = decodeString("UTF-8")

  /**
   * Decodes this ByteString using a charset to produce a String.
   */
  def decodeString(charset: String): String

  /**
   * map method that will automatically cast Int back into Byte.
   */
  final def mapI(f: Byte ⇒ Int): ByteString = map(f andThen (_.toByte))
}

object CompactByteString {
  /**
   * Creates a new CompactByteString by copying a byte array.
   */
  def apply(bytes: Array[Byte]): CompactByteString = {
    if (bytes.isEmpty) empty
    else ByteString.ByteString1C(bytes.clone)
  }

  /**
   * Creates a new CompactByteString by copying bytes.
   */
  def apply(bytes: Byte*): CompactByteString = {
    if (bytes.isEmpty) empty
    else {
      val ar = new Array[Byte](bytes.size)
      bytes.copyToArray(ar)
      CompactByteString(ar)
    }
  }

  /**
   * Creates a new CompactByteString by converting from integral numbers to bytes.
   */
  def apply[T](bytes: T*)(implicit num: Integral[T]): CompactByteString = {
    if (bytes.isEmpty) empty
    else ByteString.ByteString1C(bytes.map(x ⇒ num.toInt(x).toByte)(collection.breakOut))
  }

  /**
   * Creates a new CompactByteString by copying bytes from a ByteBuffer.
   */
  def apply(bytes: ByteBuffer): CompactByteString = {
    if (bytes.remaining < 1) empty
    else {
      val ar = new Array[Byte](bytes.remaining)
      bytes.get(ar)
      ByteString.ByteString1C(ar)
    }
  }

  /**
   * Creates a new CompactByteString by encoding a String as UTF-8.
   */
  def apply(string: String): CompactByteString = apply(string, "UTF-8")

  /**
   * Creates a new CompactByteString by encoding a String with a charset.
   */
  def apply(string: String, charset: String): CompactByteString = {
    if (string.isEmpty) empty
    else ByteString.ByteString1C(string.getBytes(charset))
  }

  /**
   * Creates a new CompactByteString by copying length bytes starting at offset from
   * an Array.
   */
  def fromArray(array: Array[Byte], offset: Int, length: Int): CompactByteString = {
    val copyOffset = math.max(offset, 0)
    val copyLength = math.max(math.min(array.length - copyOffset, length), 0)
    if (copyLength == 0) empty
    else {
      val copyArray = new Array[Byte](copyLength)
      Array.copy(array, copyOffset, copyArray, 0, copyLength)
      ByteString.ByteString1C(copyArray)
    }
  }

  val empty: CompactByteString = ByteString.ByteString1C(Array.empty[Byte])
}

/**
 * A contiguous (i.e. non-fragmented) ByteString.
 *
 * The ByteString is guarantieed to be contiguous in memory, making certain
 * operations more efficient than on chunked ByteString instances.
 */
sealed abstract class ContByteString extends ByteString {
  def contiguous = this
}

/**
 * A compact and contiguous ByteString.
 *
 * The ByteString is guarantied to be contiguous in memory and to blocks only
 * as much memory as required for its contents.
 */
sealed abstract class CompactByteString extends ContByteString with Serializable {
  def compact = this
}

/**
 * A mutable builder for efficiently creating a [[akka.util.ByteString]].
 *
 * The created ByteString is not automatically compacted.
 */
final class ByteStringBuilder extends Builder[Byte, ByteString] {
  import ByteString.{ ByteString1C, ByteString1, ByteStrings }
  private var _length = 0
  private val _builder = new VectorBuilder[ByteString1]()
  private var _temp: Array[Byte] = _
  private var _tempLength = 0
  private var _tempCapacity = 0

  private def clearTemp() {
    if (_tempLength > 0) {
      val arr = new Array[Byte](_tempLength)
      Array.copy(_temp, 0, arr, 0, _tempLength)
      _builder += ByteString1(arr)
      _tempLength = 0
    }
  }

  private def resizeTemp(size: Int) {
    val newtemp = new Array[Byte](size)
    if (_tempLength > 0) Array.copy(_temp, 0, newtemp, 0, _tempLength)
    _temp = newtemp
    _tempCapacity = _temp.length
  }

  private def ensureTempSize(size: Int) {
    if (_tempCapacity < size || _tempCapacity == 0) {
      var newSize = if (_tempCapacity == 0) 16 else _tempCapacity * 2
      while (newSize < size) newSize *= 2
      resizeTemp(newSize)
    }
  }

  def +=(elem: Byte): this.type = {
    ensureTempSize(_tempLength + 1)
    _temp(_tempLength) = elem
    _tempLength += 1
    _length += 1
    this
  }

  override def ++=(xs: TraversableOnce[Byte]): this.type = {
    xs match {
      case b: ByteString1C ⇒
        clearTemp()
        _builder += b.toByteString1
        _length += b.length
      case b: ByteString1 ⇒
        clearTemp()
        _builder += b
        _length += b.length
      case bs: ByteStrings ⇒
        clearTemp()
        _builder ++= bs.bytestrings
        _length += bs.length
      case xs: WrappedArray.ofByte ⇒
        clearTemp()
        _builder += ByteString1(xs.array.clone)
        _length += xs.length
      case _: collection.IndexedSeq[_] ⇒
        ensureTempSize(_tempLength + xs.size)
        xs.copyToArray(_temp, _tempLength)
      case _ ⇒
        super.++=(xs)
    }
    this
  }

  def clear() {
    _builder.clear
    _length = 0
    _tempLength = 0
  }

  def result: ByteString =
    if (_length == 0) ByteString.empty
    else {
      clearTemp()
      val bytestrings = _builder.result
      if (bytestrings.size == 1)
        bytestrings.head
      else
        ByteStrings(bytestrings, _length)
    }

}
