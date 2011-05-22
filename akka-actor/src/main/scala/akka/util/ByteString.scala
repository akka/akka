package akka.util

import java.nio.ByteBuffer

import scala.collection.IndexedSeqOptimized
import scala.collection.mutable.{ Builder, ArrayBuilder }
import scala.collection.immutable.IndexedSeq
import scala.collection.generic.{ CanBuildFrom, GenericCompanion }

object ByteString {

  def apply(bytes: Array[Byte]): ByteString = new ByteString(bytes.clone)

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

  def newBuilder = new Builder[Byte, ByteString] {
    private val arrayBuilder = new ArrayBuilder.ofByte

    override def sizeHint(size: Int): Unit = arrayBuilder.sizeHint(size)

    def +=(elem: Byte): this.type = {
      arrayBuilder += elem
      this
    }

    override def ++=(xs: TraversableOnce[Byte]): this.type = {
      arrayBuilder ++= xs
      this
    }

    def clear(): Unit = arrayBuilder.clear

    def result: ByteString = apply(arrayBuilder.result)
  }

  implicit def canBuildFrom = new CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] {
    def apply(from: TraversableOnce[Byte]) = newBuilder
    def apply() = newBuilder
  }

}

final class ByteString private (private val bytes: Array[Byte]) extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteString] {

  override def newBuilder = ByteString.newBuilder

  //def seq = this

  def apply(idx: Int): Byte = bytes(idx)

  def length: Int = bytes.length

  def toArray: Array[Byte] = bytes.clone

  def asByteBuffer: ByteBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer

  def toByteBuffer: ByteBuffer = ByteBuffer.wrap(bytes.clone)

}
