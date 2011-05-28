package akka.util

import java.nio.ByteBuffer

import scala.collection.IndexedSeqOptimized
import scala.collection.mutable.{ Builder, ArrayBuilder }
import scala.collection.immutable.IndexedSeq
import scala.collection.generic.{ CanBuildFrom, GenericCompanion }

object ByteString {

  def apply(bytes: Array[Byte]): ByteString = new ByteString(bytes.clone)

  def apply(bytes: Byte*): ByteString = {
    val ar = new Array[Byte](bytes.size)
    bytes.copyToArray(ar)
    new ByteString(ar)
  }

  def apply[T](bytes: T*)(implicit num: Integral[T]): ByteString =
    new ByteString(bytes.map(x ⇒ num.toInt(x).toByte)(collection.breakOut))

  def apply(bytes: ByteBuffer): ByteString = {
    val ar = new Array[Byte](bytes.remaining)
    bytes.get(ar)
    new ByteString(ar)
  }

  def apply(string: String): ByteString = apply(string, "UTF-8")

  def apply(string: String, charset: String): ByteString = new ByteString(string.getBytes(charset))

  def concat(xss: Traversable[Byte]*): ByteString = {
    var length = 0
    val li = xss.iterator
    while (li.hasNext) {
      length += li.next.size
    }
    val ar = new Array[Byte](length)
    var pos = 0
    val i = xss.iterator
    while (i.hasNext) {
      val cur = i.next
      val len = cur.size
      cur.copyToArray(ar, pos, len)
      pos += len
    }
    new ByteString(ar)
  }

  val empty: ByteString = new ByteString(Array.empty[Byte])

  def newBuilder: Builder[Byte, ByteString] = new ArrayBuilder.ofByte mapResult apply

  implicit def canBuildFrom = new CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] {
    def apply(from: TraversableOnce[Byte]) = newBuilder
    def apply() = newBuilder
  }

}

final class ByteString private (bytes: Array[Byte], startIndex: Int, endIndex: Int) extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteString] {

  private def this(bytes: Array[Byte]) = this(bytes, 0, bytes.length)

  override protected[this] def newBuilder = ByteString.newBuilder

  def apply(idx: Int): Byte = bytes(checkRangeConvert(idx))

  private def checkRangeConvert(index: Int) = {
    val idx = index + startIndex
    if (0 <= index && idx < endIndex)
      idx
    else
      throw new IndexOutOfBoundsException(index.toString)
  }

  def length: Int = endIndex - startIndex

  override def clone: ByteString = ByteString(toArray)

  def toArray: Array[Byte] = {
    val ar = new Array[Byte](length)
    Array.copy(bytes, startIndex, ar, 0, length)
    ar
  }

  def asByteBuffer: ByteBuffer = {
    val buffer = ByteBuffer.wrap(bytes, startIndex, length).asReadOnlyBuffer
    if (buffer.remaining < bytes.length) buffer.slice
    else buffer
  }

  def toByteBuffer: ByteBuffer = ByteBuffer.wrap(toArray)

  def utf8String: String = new String(bytes, "UTF-8")

  def mapI(f: Byte ⇒ Int): ByteString = map(f andThen (_.toByte))

  override def slice(from: Int, until: Int): ByteString = {
    val newStartIndex = math.max(from, 0) + startIndex
    val newEndIndex = math.min(until, length) + startIndex
    if (newEndIndex - newStartIndex <= 0) ByteString.empty
    else new ByteString(bytes, newStartIndex, newEndIndex)
  }

  override def copyToArray[A >: Byte](xs: Array[A], start: Int, len: Int): Unit =
    Array.copy(bytes, startIndex, xs, start, math.min(math.min(length, len), xs.length - start))

}

object ByteRope {

  def apply(bytes: Array[Byte]): ByteRope = new ByteRope(Vector(ByteString(bytes)))

  def apply(bytes: Byte*): ByteRope = new ByteRope(Vector(ByteString(bytes: _*)))

  def apply[T](bytes: T*)(implicit num: Integral[T]): ByteRope = new ByteRope(Vector(ByteString(bytes: _*)(num)))

  def apply(bytes: ByteBuffer): ByteRope = new ByteRope(Vector(ByteString(bytes)))

  def apply(string: String): ByteRope = new ByteRope(Vector(ByteString(string)))

  def apply(string: String, charset: String): ByteRope = new ByteRope(Vector(ByteString(string, charset)))

  def empty: ByteRope = new ByteRope(Vector.empty)

  def newBuilder: Builder[Byte, ByteRope] = new ArrayBuilder.ofByte mapResult apply

  implicit def canBuildFrom = new CanBuildFrom[TraversableOnce[Byte], Byte, ByteRope] {
    def apply(from: TraversableOnce[Byte]) = newBuilder
    def apply() = newBuilder
  }

}

final class ByteRope private (bytestrings: Vector[ByteString]) extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteRope] {

  override protected[this] def newBuilder = ByteRope.newBuilder

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

  val length: Int = (0 /: bytestrings)(_ + _.length)

  override def slice(from: Int, until: Int): ByteRope = {
    val start = math.max(from, 0)
    val end = math.min(until, length)
    if (end - start <= 0)
      ByteRope.empty
    else {
      var pos = 0
      var seen = 0
      while (from >= seen + bytestrings(pos).length) {
        seen += bytestrings(pos).length
        pos += 1
      }
      val startpos = pos
      val startidx = start - seen
      while (until > seen + bytestrings(pos).length) {
        seen += bytestrings(pos).length
        pos += 1
      }
      val endpos = pos
      val endidx = end - seen
      if (startpos == endpos)
        new ByteRope(Vector(bytestrings(startpos).slice(startidx, endidx)))
      else
        new ByteRope(bytestrings(startpos).drop(startpos) +: bytestrings.slice(startpos + 1, endpos) :+ bytestrings(endpos).take(endidx))
    }
  }

  def :+(that: ByteString): ByteRope = new ByteRope(bytestrings :+ that)

  def toByteString: ByteString = ByteString.concat(bytestrings: _*)
}
