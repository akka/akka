/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.lang.reflect.Field
import java.nio.charset.Charset
import java.nio.{ ByteBuffer, ByteOrder }

import org.agrona.concurrent.{ ManyToManyConcurrentArrayQueue, UnsafeBuffer }
import sun.misc.Cleaner

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[remote] class OutOfBuffersException extends RuntimeException("Out of usable ByteBuffers")

/**
 * INTERNAL API
 */
private[remote] class EnvelopeBufferPool(maximumPayload: Int, maximumBuffers: Int) {
  private val availableBuffers = new ManyToManyConcurrentArrayQueue[EnvelopeBuffer](maximumBuffers)

  def acquire(): EnvelopeBuffer = {
    val buf = availableBuffers.poll()
    if (buf ne null) {
      buf.byteBuffer.clear()
      buf
    } else {
      val newBuf = new EnvelopeBuffer(ByteBuffer.allocateDirect(maximumPayload))
      newBuf.byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
      newBuf
    }
  }

  def release(buffer: EnvelopeBuffer) = if (!availableBuffers.offer(buffer)) buffer.tryForceDrop()

}

/**
 * INTERNAL API
 */
private[remote] object EnvelopeBuffer {

  val TagTypeMask = 0xFF000000
  val TagValueMask = 0x0000FFFF

  val VersionOffset = 0
  val UidOffset = 4
  val SenderActorRefTagOffset = 8
  val RecipientActorRefTagOffset = 12
  val SerializerTagOffset = 16
  val ClassManifestTagOffset = 24

  val LiteralsSectionOffset = 32

  val UsAscii = Charset.forName("US-ASCII")

  val DeadLettersCode = 0
}

/**
 * INTERNAL API
 */
private[remote] trait LiteralCompressionTable {

  def compressActorRef(ref: String): Int
  def decompressActorRef(idx: Int): String

  def compressSerializer(serializer: String): Int
  def decompressSerializer(idx: Int): String

  def compressClassManifest(manifest: String): Int
  def decompressClassManifest(idx: Int): String

}

object HeaderBuilder {
  def apply(compressionTable: LiteralCompressionTable): HeaderBuilder = new HeaderBuilderImpl(compressionTable)
}

/**
 * INTERNAL API
 */
sealed trait HeaderBuilder {
  def version_=(v: Int): Unit
  def version: Int

  def uid_=(u: Int): Unit
  def uid: Int

  def senderActorRef_=(ref: String): Unit
  def senderActorRef: String

  def setNoSender(): Unit

  def recipientActorRef_=(ref: String): Unit
  def recipientActorRef: String

  def serializer_=(serializer: String): Unit
  def serializer: String

  def classManifest_=(manifest: String): Unit
  def classManifest: String
}

/**
 * INTERNAL API
 */
private[remote] final class HeaderBuilderImpl(val compressionTable: LiteralCompressionTable) extends HeaderBuilder {
  var version: Int = _
  var uid: Int = _

  // Fields only available for EnvelopeBuffer
  var _senderActorRef: String = null
  var _senderActorRefIdx: Int = -1
  var _recipientActorRef: String = null
  var _recipientActorRefIdx: Int = -1

  var _serializer: String = null
  var _serializerIdx: Int = -1
  var _classManifest: String = null
  var _classManifestIdx: Int = -1

  def senderActorRef_=(ref: String): Unit = {
    _senderActorRef = ref
    _senderActorRefIdx = compressionTable.compressActorRef(ref)
  }

  def setNoSender(): Unit = {
    _senderActorRef = null
    _senderActorRefIdx = EnvelopeBuffer.DeadLettersCode
  }

  def senderActorRef: String = {
    if (_senderActorRef ne null) _senderActorRef
    else {
      _senderActorRef = compressionTable.decompressActorRef(_senderActorRefIdx)
      _senderActorRef
    }
  }

  def recipientActorRef_=(ref: String): Unit = {
    _recipientActorRef = ref
    _recipientActorRefIdx = compressionTable.compressActorRef(ref)
  }

  def recipientActorRef: String = {
    if (_recipientActorRef ne null) _recipientActorRef
    else {
      _recipientActorRef = compressionTable.decompressActorRef(_recipientActorRefIdx)
      _recipientActorRef
    }
  }

  override def serializer_=(serializer: String): Unit = {
    _serializer = serializer
    _serializerIdx = compressionTable.compressSerializer(serializer)
  }

  override def serializer: String = {
    if (_serializer ne null) _serializer
    else {
      _serializer = compressionTable.decompressSerializer(_serializerIdx)
      _serializer
    }
  }

  override def classManifest_=(manifest: String): Unit = {
    _classManifest = manifest
    _classManifestIdx = compressionTable.compressClassManifest(manifest)
  }

  override def classManifest: String = {
    if (_classManifest ne null) _classManifest
    else {
      _classManifest = compressionTable.decompressClassManifest(_classManifestIdx)
      _classManifest
    }
  }

  override def toString = s"HeaderBuilderImpl($version, $uid, ${_senderActorRef}, ${_senderActorRefIdx}, ${_recipientActorRef}, ${_recipientActorRefIdx}, ${_serializer}, ${_serializerIdx}, ${_classManifest}, ${_classManifestIdx})"
}

/**
 * INTERNAL API
 */
private[remote] final class EnvelopeBuffer(val byteBuffer: ByteBuffer) {
  import EnvelopeBuffer._
  val aeronBuffer = new UnsafeBuffer(byteBuffer)

  private val cleanerField: Field = try {
    val cleaner = byteBuffer.getClass.getDeclaredField("cleaner")
    cleaner.setAccessible(true)
    cleaner
  } catch {
    case NonFatal(_) ⇒ null
  }

  def tryForceDrop(): Unit = {
    if (cleanerField ne null) cleanerField.get(byteBuffer) match {
      case cleaner: Cleaner ⇒ cleaner.clean()
      case _                ⇒
    }
  }

  def writeHeader(h: HeaderBuilder): Unit = {
    val header = h.asInstanceOf[HeaderBuilderImpl]
    byteBuffer.clear()

    // Write fixed length parts
    byteBuffer.putInt(header.version)
    byteBuffer.putInt(header.uid)

    // Write compressable, variable-length parts always to the actual position of the buffer
    // Write tag values explicitly in their proper offset
    byteBuffer.position(LiteralsSectionOffset)

    // Serialize sender
    if (header._senderActorRefIdx != -1)
      byteBuffer.putInt(SenderActorRefTagOffset, header._senderActorRefIdx | TagTypeMask)
    else
      writeLiteral(SenderActorRefTagOffset, header._senderActorRef)

    // Serialize recipient
    if (header._recipientActorRefIdx != -1)
      byteBuffer.putInt(RecipientActorRefTagOffset, header._recipientActorRefIdx | TagTypeMask)
    else
      writeLiteral(RecipientActorRefTagOffset, header._recipientActorRef)

    // Serialize serializer
    if (header._serializerIdx != -1)
      byteBuffer.putInt(SerializerTagOffset, header._serializerIdx | TagTypeMask)
    else
      writeLiteral(SerializerTagOffset, header._serializer)

    // Serialize class manifest
    if (header._classManifestIdx != -1)
      byteBuffer.putInt(ClassManifestTagOffset, header._classManifestIdx | TagTypeMask)
    else
      writeLiteral(ClassManifestTagOffset, header._classManifest)
  }

  def parseHeader(h: HeaderBuilder): Unit = {
    val header = h.asInstanceOf[HeaderBuilderImpl]

    // Read fixed length parts
    header.version = byteBuffer.getInt
    header.uid = byteBuffer.getInt

    // Read compressable, variable-length parts always from the actual position of the buffer
    // Read tag values explicitly from their proper offset
    byteBuffer.position(LiteralsSectionOffset)

    // Deserialize sender
    val senderTag = byteBuffer.getInt(SenderActorRefTagOffset)
    if ((senderTag & TagTypeMask) != 0) {
      val idx = senderTag & TagValueMask
      header._senderActorRef = null
      header._senderActorRefIdx = idx
    } else {
      header._senderActorRef = readLiteral()
    }

    // Deserialize recipient
    val recipientTag = byteBuffer.getInt(RecipientActorRefTagOffset)
    if ((recipientTag & TagTypeMask) != 0) {
      val idx = recipientTag & TagValueMask
      header._recipientActorRef = null
      header._recipientActorRefIdx = idx
    } else {
      header._recipientActorRef = readLiteral()
    }

    // Deserialize serializer
    val serializerTag = byteBuffer.getInt(SerializerTagOffset)
    if ((serializerTag & TagTypeMask) != 0) {
      val idx = serializerTag & TagValueMask
      header._serializer = null
      header._serializerIdx = idx
    } else {
      header._serializer = readLiteral()
    }

    // Deserialize class manifest
    val classManifestTag = byteBuffer.getInt(ClassManifestTagOffset)
    if ((classManifestTag & TagTypeMask) != 0) {
      val idx = classManifestTag & TagValueMask
      header._classManifest = null
      header._classManifestIdx = idx
    } else {
      header._classManifest = readLiteral()
    }
  }

  private def readLiteral(): String = {
    val length = byteBuffer.getShort
    val bytes = Array.ofDim[Byte](length)
    byteBuffer.get(bytes)
    new String(bytes, UsAscii)
  }

  private def writeLiteral(tagOffset: Int, literal: String): Unit = {
    if (literal.length > 65535)
      throw new IllegalArgumentException("Literals longer than 65535 cannot be encoded in the envelope")

    val literalBytes = literal.getBytes(UsAscii)
    byteBuffer.putInt(tagOffset, byteBuffer.position())
    byteBuffer.putShort(literalBytes.length.toShort)
    byteBuffer.put(literalBytes)
  }

}