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

  val empty: ByteString = new ByteString(Array.empty[Byte])

  def newBuilder: Builder[Byte, ByteString] = new ArrayBuilder.ofByte mapResult apply

  implicit def canBuildFrom = new CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] {
    def apply(from: TraversableOnce[Byte]) = newBuilder
    def apply() = newBuilder
  }

}

final class ByteString private (private val bytes: Array[Byte]) extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteString] {

  override protected[this] def newBuilder = ByteString.newBuilder

  def apply(idx: Int): Byte = bytes(idx)

  def length: Int = bytes.length

  override def clone: ByteString = ByteString(bytes)

  def toArray: Array[Byte] = bytes.clone

  def asByteBuffer: ByteBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer

  def toByteBuffer: ByteBuffer = ByteBuffer.wrap(bytes.clone)

  def mapI(f: Byte ⇒ Int): ByteString = map(f andThen (_.toByte))

}
