/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.util

import java.io.{ ObjectInputStream, ObjectOutputStream }
import java.nio.{ ByteBuffer, ByteOrder }
import java.lang.{ Iterable ⇒ JIterable }
import scala.annotation.varargs
import scala.collection.IndexedSeqOptimized
import scala.collection.mutable.{ Builder, WrappedArray }
import scala.collection.immutable
import scala.collection.immutable.{ IndexedSeq, VectorBuilder }
import scala.collection.generic.CanBuildFrom
import scala.reflect.ClassTag
import java.nio.charset.StandardCharsets

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
  def apply(string: String): ByteString = apply(string, UTF_8)

  /**
   * Creates a new ByteString by encoding a String with a charset.
   */
  def apply(string: String, charset: String): ByteString = CompactByteString(string, charset)

  /**
   * Creates a new ByteString by copying a byte array.
   */
  def fromArray(array: Array[Byte]): ByteString = apply(array)

  /**
   * Creates a new ByteString by copying length bytes starting at offset from
   * an Array.
   */
  def fromArray(array: Array[Byte], offset: Int, length: Int): ByteString =
    CompactByteString.fromArray(array, offset, length)

  /**
   * JAVA API
   * Creates a new ByteString by copying an int array by converting from integral numbers to bytes.
   */
  @varargs
  def fromInts(array: Int*): ByteString =
    apply(array: _*)(scala.math.Numeric.IntIsIntegral)

  /**
   * Creates a new ByteString which will contain the UTF-8 representation of the given String
   */
  def fromString(string: String): ByteString = apply(string)

  /**
   * Creates a new ByteString which will contain the representation of the given String in the given charset
   */
  def fromString(string: String, charset: String): ByteString = apply(string, charset)

  /**
   * Standard "UTF-8" charset
   */
  val UTF_8: String = StandardCharsets.UTF_8.name()

  /**
   * Creates a new ByteString by copying bytes out of a ByteBuffer.
   */
  def fromByteBuffer(buffer: ByteBuffer): ByteString = apply(buffer)

  val empty: ByteString = CompactByteString(Array.empty[Byte])

  def newBuilder: ByteStringBuilder = new ByteStringBuilder

  /** Java API */
  def createBuilder: ByteStringBuilder = new ByteStringBuilder

  implicit val canBuildFrom: CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] =
    new CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] {
      def apply(ignore: TraversableOnce[Byte]): ByteStringBuilder = newBuilder
      def apply(): ByteStringBuilder = newBuilder
    }

  private[akka] object ByteString1C extends Companion {
    def apply(bytes: Array[Byte]): ByteString1C = new ByteString1C(bytes)
    val SerializationIdentity = 1.toByte

    def readFromInputStream(is: ObjectInputStream): ByteString1C = {
      val length = is.readInt()
      val arr = new Array[Byte](length)
      is.read(arr, 0, length)
      ByteString1C(arr)
    }
  }

  /**
   * A compact (unsliced) and unfragmented ByteString, implementation of ByteString1C.
   */
  @SerialVersionUID(3956956327691936932L)
  final class ByteString1C private (private val bytes: Array[Byte]) extends CompactByteString {
    def apply(idx: Int): Byte = bytes(idx)

    override def length: Int = bytes.length

    override def iterator: ByteIterator.ByteArrayIterator = ByteIterator.ByteArrayIterator(bytes, 0, bytes.length)

    private[akka] def toByteString1: ByteString1 = ByteString1(bytes)

    private[akka] def byteStringCompanion = ByteString1C

    def asByteBuffer: ByteBuffer = toByteString1.asByteBuffer

    def asByteBuffers: scala.collection.immutable.Iterable[ByteBuffer] = List(asByteBuffer)

    def decodeString(charset: String): String =
      if (isEmpty) "" else new String(bytes, charset)

    def ++(that: ByteString): ByteString =
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else toByteString1 ++ that

    override def slice(from: Int, until: Int): ByteString =
      if ((from != 0) || (until != length)) toByteString1.slice(from, until)
      else this

    private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit =
      toByteString1.writeToOutputStream(os)
  }

  private[akka] object ByteString1 extends Companion {
    val empty: ByteString1 = new ByteString1(Array.empty[Byte])
    def apply(bytes: Array[Byte]): ByteString1 = ByteString1(bytes, 0, bytes.length)
    def apply(bytes: Array[Byte], startIndex: Int, length: Int): ByteString1 =
      if (length == 0) empty else new ByteString1(bytes, startIndex, length)

    val SerializationIdentity = 0.toByte

    def readFromInputStream(is: ObjectInputStream): ByteString1 =
      ByteString1C.readFromInputStream(is).toByteString1
  }

  /**
   * An unfragmented ByteString.
   */
  final class ByteString1 private (private val bytes: Array[Byte], private val startIndex: Int, val length: Int) extends ByteString with Serializable {

    private def this(bytes: Array[Byte]) = this(bytes, 0, bytes.length)

    def apply(idx: Int): Byte = bytes(checkRangeConvert(idx))

    override def iterator: ByteIterator.ByteArrayIterator =
      ByteIterator.ByteArrayIterator(bytes, startIndex, startIndex + length)

    private def checkRangeConvert(index: Int): Int = {
      if (0 <= index && length > index)
        index + startIndex
      else
        throw new IndexOutOfBoundsException(index.toString)
    }

    private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit = {
      os.writeInt(length)
      os.write(bytes, startIndex, length)
    }

    def isCompact: Boolean = (length == bytes.length)

    private[akka] def byteStringCompanion = ByteString1

    def compact: CompactByteString =
      if (isCompact) ByteString1C(bytes) else ByteString1C(toArray)

    def asByteBuffer: ByteBuffer = {
      val buffer = ByteBuffer.wrap(bytes, startIndex, length).asReadOnlyBuffer
      if (buffer.remaining < bytes.length) buffer.slice
      else buffer
    }

    def asByteBuffers: scala.collection.immutable.Iterable[ByteBuffer] = List(asByteBuffer)

    def decodeString(charset: String): String =
      new String(if (length == bytes.length) bytes else toArray, charset)

    def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else that match {
        case b: ByteString1C ⇒ ByteStrings(this, b.toByteString1)
        case b: ByteString1 ⇒
          if ((bytes eq b.bytes) && (startIndex + length == b.startIndex))
            new ByteString1(bytes, startIndex, length + b.length)
          else ByteStrings(this, b)
        case bs: ByteStrings ⇒ ByteStrings(this, bs)
      }
    }

    protected def writeReplace(): AnyRef = new SerializationProxy(this)
  }

  private[akka] object ByteStrings extends Companion {
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
      if (b1.isEmpty)
        if (b2.isEmpty) 0 else 2
      else if (b2.isEmpty) 1 else 3

    val SerializationIdentity = 2.toByte

    def readFromInputStream(is: ObjectInputStream): ByteStrings = {
      val nByteStrings = is.readInt()

      val builder = new VectorBuilder[ByteString1]
      var length = 0

      builder.sizeHint(nByteStrings)

      for (_ ← 0 until nByteStrings) {
        val bs = ByteString1.readFromInputStream(is)
        builder += bs
        length += bs.length
      }

      new ByteStrings(builder.result(), length)
    }
  }

  /**
   * A ByteString with 2 or more fragments.
   */
  final class ByteStrings private (private[akka] val bytestrings: Vector[ByteString1], val length: Int) extends ByteString with Serializable {
    if (bytestrings.isEmpty) throw new IllegalArgumentException("bytestrings must not be empty")

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

    override def iterator: ByteIterator.MultiByteArrayIterator =
      ByteIterator.MultiByteArrayIterator(bytestrings.toStream map { _.iterator })

    def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else that match {
        case b: ByteString1C ⇒ ByteStrings(this, b.toByteString1)
        case b: ByteString1  ⇒ ByteStrings(this, b)
        case bs: ByteStrings ⇒ ByteStrings(this, bs)
      }
    }

    private[akka] def byteStringCompanion = ByteStrings

    def isCompact: Boolean = if (bytestrings.length == 1) bytestrings.head.isCompact else false

    def compact: CompactByteString = {
      if (isCompact) bytestrings.head.compact
      else {
        val ar = new Array[Byte](length)
        var pos = 0
        bytestrings foreach { b ⇒
          b.copyToArray(ar, pos, b.length)
          pos += b.length
        }
        ByteString1C(ar)
      }
    }

    def asByteBuffer: ByteBuffer = compact.asByteBuffer

    def asByteBuffers: scala.collection.immutable.Iterable[ByteBuffer] = bytestrings map { _.asByteBuffer }

    def decodeString(charset: String): String = compact.decodeString(charset)

    private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit = {
      os.writeInt(bytestrings.length)
      bytestrings.foreach(_.writeToOutputStream(os))
    }

    protected def writeReplace(): AnyRef = new SerializationProxy(this)
  }

  @SerialVersionUID(1L)
  private class SerializationProxy(@transient private var orig: ByteString) extends Serializable {
    private def writeObject(out: ObjectOutputStream) {
      out.writeByte(orig.byteStringCompanion.SerializationIdentity)
      orig.writeToOutputStream(out)
    }

    private def readObject(in: ObjectInputStream) {
      val serializationId = in.readByte()

      orig = Companion(from = serializationId).readFromInputStream(in)
    }

    private def readResolve(): AnyRef = orig
  }

  private[akka] object Companion {
    private val companionMap = Seq(ByteString1, ByteString1C, ByteStrings).
      map(x ⇒ x.SerializationIdentity → x).toMap.
      withDefault(x ⇒ throw new IllegalArgumentException("Invalid serialization id " + x))

    def apply(from: Byte): Companion = companionMap(from)
  }

  private[akka] sealed trait Companion {
    def SerializationIdentity: Byte
    def readFromInputStream(is: ObjectInputStream): ByteString
  }
}

/**
 * A rope-like immutable data structure containing bytes.
 * The goal of this structure is to reduce copying of arrays
 * when concatenating and slicing sequences of bytes,
 * and also providing a thread safe way of working with bytes.
 *
 * TODO: Add performance characteristics
 */
sealed abstract class ByteString extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteString] {
  def apply(idx: Int): Byte
  private[akka] def byteStringCompanion: ByteString.Companion

  override protected[this] def newBuilder: ByteStringBuilder = ByteString.newBuilder

  // *must* be overridden by derived classes. This construction is necessary
  // to specialize the return type, as the method is already implemented in
  // a parent trait.
  override def iterator: ByteIterator = throw new UnsupportedOperationException("Method iterator is not implemented in ByteString")

  override def head: Byte = apply(0)
  override def tail: ByteString = drop(1)
  override def last: Byte = apply(length - 1)
  override def init: ByteString = dropRight(1)

  override def slice(from: Int, until: Int): ByteString =
    if ((from == 0) && (until == length)) this
    else iterator.slice(from, until).toByteString

  override def take(n: Int): ByteString = slice(0, n)
  override def takeRight(n: Int): ByteString = slice(length - n, length)
  override def drop(n: Int): ByteString = slice(n, length)
  override def dropRight(n: Int): ByteString = slice(0, length - n)

  override def takeWhile(p: Byte ⇒ Boolean): ByteString = iterator.takeWhile(p).toByteString
  override def dropWhile(p: Byte ⇒ Boolean): ByteString = iterator.dropWhile(p).toByteString
  override def span(p: Byte ⇒ Boolean): (ByteString, ByteString) =
    { val (a, b) = iterator.span(p); (a.toByteString, b.toByteString) }

  override def splitAt(n: Int): (ByteString, ByteString) = (take(n), drop(n))

  override def indexWhere(p: Byte ⇒ Boolean): Int = iterator.indexWhere(p)
  override def indexOf[B >: Byte](elem: B): Int = iterator.indexOf(elem)

  override def toString(): String = {
    val maxSize = 100
    if (size > maxSize)
      take(maxSize).toString + s"... and [${size - maxSize}] more"
    else
      super.toString
  }

  /**
   * Java API: copy this ByteString into a fresh byte array
   *
   * @return this ByteString copied into a byte array
   */
  protected[ByteString] def toArray: Array[Byte] = toArray[Byte] // protected[ByteString] == public to Java but hidden to Scala * fnizz *

  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = iterator.toArray
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit =
    iterator.copyToArray(xs, start, len)

  override def foreach[@specialized U](f: Byte ⇒ U): Unit = iterator foreach f

  private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit

  /**
   * Efficiently concatenate another ByteString.
   */
  def ++(that: ByteString): ByteString

  /**
   * Java API: efficiently concatenate another ByteString.
   */
  def concat(that: ByteString): ByteString = this ++ that

  /**
   * Copy as many bytes as possible to a ByteBuffer, starting from it's
   * current position. This method will not overflow the buffer.
   *
   * @param buffer a ByteBuffer to copy bytes to
   * @return the number of bytes actually copied
   */
  def copyToBuffer(buffer: ByteBuffer): Int = iterator.copyToBuffer(buffer)

  /**
   * Create a new ByteString with all contents compacted into a single,
   * full byte array.
   * If isCompact returns true, compact is an O(1) operation, but
   * might return a different object with an optimized implementation.
   */
  def compact: CompactByteString

  /**
   * Check whether this ByteString is compact in memory.
   * If the ByteString is compact, it might, however, not be represented
   * by an object that takes full advantage of that fact. Use compact to
   * get such an object.
   */
  def isCompact: Boolean

  /**
   * Returns a read-only ByteBuffer that directly wraps this ByteString
   * if it is not fragmented.
   */
  def asByteBuffer: ByteBuffer

  /**
   * Scala API: Returns an immutable Iterable of read-only ByteBuffers that directly wraps this ByteStrings
   * all fragments. Will always have at least one entry.
   */
  def asByteBuffers: immutable.Iterable[ByteBuffer]

  /**
   * Java API: Returns an Iterable of read-only ByteBuffers that directly wraps this ByteStrings
   * all fragments. Will always have at least one entry.
   */
  def getByteBuffers(): JIterable[ByteBuffer] = {
    import scala.collection.JavaConverters.asJavaIterableConverter
    asByteBuffers.asJava
  }

  /**
   * Creates a new ByteBuffer with a copy of all bytes contained in this
   * ByteString.
   */
  def toByteBuffer: ByteBuffer = ByteBuffer.wrap(toArray)

  /**
   * Decodes this ByteString as a UTF-8 encoded String.
   */
  final def utf8String: String = decodeString(ByteString.UTF_8)

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
  def apply(bytes: Array[Byte]): CompactByteString =
    if (bytes.isEmpty) empty else ByteString.ByteString1C(bytes.clone)

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
  def apply(string: String): CompactByteString = apply(string, ByteString.UTF_8)

  /**
   * Creates a new CompactByteString by encoding a String with a charset.
   */
  def apply(string: String, charset: String): CompactByteString =
    if (string.isEmpty) empty else ByteString.ByteString1C(string.getBytes(charset))

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
 * A compact ByteString.
 *
 * The ByteString is guarantied to be contiguous in memory and to use only
 * as much memory as required for its contents.
 */
sealed abstract class CompactByteString extends ByteString with Serializable {
  def isCompact: Boolean = true
  def compact: this.type = this
}

/**
 * A mutable builder for efficiently creating a [[akka.util.ByteString]].
 *
 * The created ByteString is not automatically compacted.
 */
final class ByteStringBuilder extends Builder[Byte, ByteString] {
  builder ⇒

  import ByteString.{ ByteString1C, ByteString1, ByteStrings }
  private var _length: Int = 0
  private val _builder: VectorBuilder[ByteString1] = new VectorBuilder[ByteString1]()
  private var _temp: Array[Byte] = _
  private var _tempLength: Int = 0
  private var _tempCapacity: Int = 0

  protected def fillArray(len: Int)(fill: (Array[Byte], Int) ⇒ Unit): this.type = {
    ensureTempSize(_tempLength + len)
    fill(_temp, _tempLength)
    _tempLength += len
    _length += len
    this
  }

  @inline protected final def fillByteBuffer(len: Int, byteOrder: ByteOrder)(fill: ByteBuffer ⇒ Unit): this.type = {
    fillArray(len) {
      case (array, start) ⇒
        val buffer = ByteBuffer.wrap(array, start, len)
        buffer.order(byteOrder)
        fill(buffer)
    }
  }

  def length: Int = _length

  override def sizeHint(len: Int): Unit = {
    resizeTemp(len - (_length - _tempLength))
  }

  private def clearTemp(): Unit = {
    if (_tempLength > 0) {
      val arr = new Array[Byte](_tempLength)
      Array.copy(_temp, 0, arr, 0, _tempLength)
      _builder += ByteString1(arr)
      _tempLength = 0
    }
  }

  private def resizeTemp(size: Int): Unit = {
    val newtemp = new Array[Byte](size)
    if (_tempLength > 0) Array.copy(_temp, 0, newtemp, 0, _tempLength)
    _temp = newtemp
    _tempCapacity = _temp.length
  }

  @inline private def shouldResizeTempFor(size: Int): Boolean = _tempCapacity < size || _tempCapacity == 0

  private def ensureTempSize(size: Int): Unit = {
    if (shouldResizeTempFor(size)) {
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
        putByteArrayUnsafe(xs.array.clone)
      case seq: collection.IndexedSeq[Byte] if shouldResizeTempFor(seq.length) ⇒
        val copied = new Array[Byte](seq.length)
        seq.copyToArray(copied)

        clearTemp()
        _builder += ByteString.ByteString1(copied)
        _length += seq.length
      case seq: collection.IndexedSeq[_] ⇒
        ensureTempSize(_tempLength + xs.size)
        xs.copyToArray(_temp, _tempLength)
        _tempLength += seq.length
        _length += seq.length
      case _ ⇒
        super.++=(xs)
    }
    this
  }

  private[akka] def putByteArrayUnsafe(xs: Array[Byte]): this.type = {
    clearTemp()
    _builder += ByteString1(xs)
    _length += xs.length
    this
  }

  /**
   * Java API: append a ByteString to this builder.
   */
  def append(bs: ByteString): this.type = this ++= bs

  /**
   * Add a single Byte to this builder.
   */
  def putByte(x: Byte): this.type = this += x

  /**
   * Add a single Short to this builder.
   */
  def putShort(x: Int)(implicit byteOrder: ByteOrder): this.type = {
    if (byteOrder == ByteOrder.BIG_ENDIAN) {
      this += (x >>> 8).toByte
      this += (x >>> 0).toByte
    } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
      this += (x >>> 0).toByte
      this += (x >>> 8).toByte
    } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
  }

  /**
   * Add a single Int to this builder.
   */
  def putInt(x: Int)(implicit byteOrder: ByteOrder): this.type = {
    fillArray(4) { (target, offset) ⇒
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        target(offset + 0) = (x >>> 24).toByte
        target(offset + 1) = (x >>> 16).toByte
        target(offset + 2) = (x >>> 8).toByte
        target(offset + 3) = (x >>> 0).toByte
      } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        target(offset + 0) = (x >>> 0).toByte
        target(offset + 1) = (x >>> 8).toByte
        target(offset + 2) = (x >>> 16).toByte
        target(offset + 3) = (x >>> 24).toByte
      } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
    }
    this
  }

  /**
   * Add a single Long to this builder.
   */
  def putLong(x: Long)(implicit byteOrder: ByteOrder): this.type = {
    fillArray(8) { (target, offset) ⇒
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        target(offset + 0) = (x >>> 56).toByte
        target(offset + 1) = (x >>> 48).toByte
        target(offset + 2) = (x >>> 40).toByte
        target(offset + 3) = (x >>> 32).toByte
        target(offset + 4) = (x >>> 24).toByte
        target(offset + 5) = (x >>> 16).toByte
        target(offset + 6) = (x >>> 8).toByte
        target(offset + 7) = (x >>> 0).toByte
      } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        target(offset + 0) = (x >>> 0).toByte
        target(offset + 1) = (x >>> 8).toByte
        target(offset + 2) = (x >>> 16).toByte
        target(offset + 3) = (x >>> 24).toByte
        target(offset + 4) = (x >>> 32).toByte
        target(offset + 5) = (x >>> 40).toByte
        target(offset + 6) = (x >>> 48).toByte
        target(offset + 7) = (x >>> 56).toByte
      } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
    }
    this
  }

  /**
   * Add the `n` least significant bytes of the given Long to this builder.
   */
  def putLongPart(x: Long, n: Int)(implicit byteOrder: ByteOrder): this.type = {
    fillArray(n) { (target, offset) ⇒
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        val start = n * 8 - 8
        (0 until n) foreach { i ⇒ target(offset + i) = (x >>> start - 8 * i).toByte }
      } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        (0 until n) foreach { i ⇒ target(offset + i) = (x >>> 8 * i).toByte }
      } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
    }
  }

  /**
   * Add a single Float to this builder.
   */
  def putFloat(x: Float)(implicit byteOrder: ByteOrder): this.type =
    putInt(java.lang.Float.floatToRawIntBits(x))(byteOrder)

  /**
   * Add a single Double to this builder.
   */
  def putDouble(x: Double)(implicit byteOrder: ByteOrder): this.type =
    putLong(java.lang.Double.doubleToRawLongBits(x))(byteOrder)

  /**
   * Add a number of Bytes from an array to this builder.
   */
  def putBytes(array: Array[Byte]): this.type =
    putBytes(array, 0, array.length)

  /**
   * Add a number of Bytes from an array to this builder.
   */
  def putBytes(array: Array[Byte], start: Int, len: Int): this.type =
    fillArray(len) { case (target, targetOffset) ⇒ Array.copy(array, start, target, targetOffset, len) }

  /**
   * Add a number of Shorts from an array to this builder.
   */
  def putShorts(array: Array[Short])(implicit byteOrder: ByteOrder): this.type =
    putShorts(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Shorts from an array to this builder.
   */
  def putShorts(array: Array[Short], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 2, byteOrder) { _.asShortBuffer.put(array, start, len) }

  /**
   * Add a number of Ints from an array to this builder.
   */
  def putInts(array: Array[Int])(implicit byteOrder: ByteOrder): this.type =
    putInts(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Ints from an array to this builder.
   */
  def putInts(array: Array[Int], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 4, byteOrder) { _.asIntBuffer.put(array, start, len) }

  /**
   * Add a number of Longs from an array to this builder.
   */
  def putLongs(array: Array[Long])(implicit byteOrder: ByteOrder): this.type =
    putLongs(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Longs from an array to this builder.
   */
  def putLongs(array: Array[Long], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 8, byteOrder) { _.asLongBuffer.put(array, start, len) }

  /**
   * Add a number of Floats from an array to this builder.
   */
  def putFloats(array: Array[Float])(implicit byteOrder: ByteOrder): this.type =
    putFloats(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Floats from an array to this builder.
   */
  def putFloats(array: Array[Float], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 4, byteOrder) { _.asFloatBuffer.put(array, start, len) }

  /**
   * Add a number of Doubles from an array to this builder.
   */
  def putDoubles(array: Array[Double])(implicit byteOrder: ByteOrder): this.type =
    putDoubles(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Doubles from an array to this builder.
   */
  def putDoubles(array: Array[Double], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 8, byteOrder) { _.asDoubleBuffer.put(array, start, len) }

  def clear(): Unit = {
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

  /**
   * Directly wraps this ByteStringBuilder in an OutputStream. Write
   * operations on the stream are forwarded to the builder.
   */
  def asOutputStream: java.io.OutputStream = new java.io.OutputStream {
    def write(b: Int): Unit = builder += b.toByte

    override def write(b: Array[Byte], off: Int, len: Int): Unit = { builder.putBytes(b, off, len) }
  }

  /**
   * Tests whether this ByteStringBuilder is empty.
   */
  def isEmpty: Boolean = _length == 0

  /**
   * Tests whether this ByteStringBuilder is not empty.
   */
  def nonEmpty: Boolean = _length > 0
}
