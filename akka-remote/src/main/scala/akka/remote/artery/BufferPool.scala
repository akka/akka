/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.lang.reflect.Field
import java.nio.charset.Charset
import java.nio.{ ByteBuffer, ByteOrder }

import org.agrona.concurrent.{ ManyToManyConcurrentArrayQueue, UnsafeBuffer }
import sun.misc.Cleaner
import akka.util.Unsafe

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

  def release(buffer: EnvelopeBuffer) = if (!availableBuffers.offer(buffer)) buffer.tryCleanDirectByteBuffer()

}

/**
 * INTERNAL API
 */
private[remote] object EnvelopeBuffer {

  val TagTypeMask = 0xFF000000
  val TagValueMask = 0x0000FFFF

  val VersionOffset = 0 // Int
  val UidOffset = 4 // Long
  val SerializerOffset = 12 // Int
  val SenderActorRefTagOffset = 16 // Int
  val RecipientActorRefTagOffset = 20 // Int
  val ClassManifestTagOffset = 24 // Int

  val LiteralsSectionOffset = 28

  val UsAscii = Charset.forName("US-ASCII")

  val DeadLettersCode = 0

  // accessing the internal char array of String when writing literal strings to ByteBuffer
  val StringValueFieldOffset = Unsafe.instance.objectFieldOffset(classOf[String].getDeclaredField("value"))
}

/**
 * INTERNAL API
 */
private[remote] trait LiteralCompressionTable {

  def compressActorRef(ref: String): Int
  def decompressActorRef(idx: Int): String

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

  def uid_=(u: Long): Unit
  def uid: Long

  def senderActorRef_=(ref: String): Unit
  def senderActorRef: String

  def setNoSender(): Unit
  def isNoSender: Boolean

  def setNoRecipient(): Unit
  def isNoRecipient: Boolean

  def recipientActorRef_=(ref: String): Unit
  def recipientActorRef: String

  def serializer_=(serializer: Int): Unit
  def serializer: Int

  def manifest_=(manifest: String): Unit
  def manifest: String
}

/**
 * INTERNAL API
 */
private[remote] final class HeaderBuilderImpl(val compressionTable: LiteralCompressionTable) extends HeaderBuilder {
  var version: Int = _
  var uid: Long = _

  // Fields only available for EnvelopeBuffer
  var _senderActorRef: String = null
  var _senderActorRefIdx: Int = -1
  var _recipientActorRef: String = null
  var _recipientActorRefIdx: Int = -1

  var _serializer: Int = _
  var _manifest: String = null
  var _manifestIdx: Int = -1

  def senderActorRef_=(ref: String): Unit = {
    _senderActorRef = ref
    _senderActorRefIdx = compressionTable.compressActorRef(ref)
  }

  def setNoSender(): Unit = {
    _senderActorRef = null
    _senderActorRefIdx = EnvelopeBuffer.DeadLettersCode
  }

  def isNoSender: Boolean =
    (_senderActorRef eq null) && _senderActorRefIdx == EnvelopeBuffer.DeadLettersCode

  def senderActorRef: String = {
    if (_senderActorRef ne null) _senderActorRef
    else {
      _senderActorRef = compressionTable.decompressActorRef(_senderActorRefIdx)
      _senderActorRef
    }
  }

  def setNoRecipient(): Unit = {
    _recipientActorRef = null
    _recipientActorRefIdx = EnvelopeBuffer.DeadLettersCode
  }

  def isNoRecipient: Boolean =
    (_recipientActorRef eq null) && _recipientActorRefIdx == EnvelopeBuffer.DeadLettersCode

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

  override def serializer_=(serializer: Int): Unit = {
    _serializer = serializer
  }

  override def serializer: Int =
    _serializer

  override def manifest_=(manifest: String): Unit = {
    _manifest = manifest
    _manifestIdx = compressionTable.compressClassManifest(manifest)
  }

  override def manifest: String = {
    if (_manifest ne null) _manifest
    else {
      _manifest = compressionTable.decompressClassManifest(_manifestIdx)
      _manifest
    }
  }

  override def toString = s"HeaderBuilderImpl($version, $uid, ${_senderActorRef}, ${_senderActorRefIdx}, ${_recipientActorRef}, ${_recipientActorRefIdx}, ${_serializer}, ${_manifest}, ${_manifestIdx})"
}

/**
 * INTERNAL API
 */
private[remote] final class EnvelopeBuffer(val byteBuffer: ByteBuffer) {
  import EnvelopeBuffer._
  val aeronBuffer = new UnsafeBuffer(byteBuffer)

  private var literalChars = Array.ofDim[Char](64)
  private var literalBytes = Array.ofDim[Byte](64)

  def writeHeader(h: HeaderBuilder): Unit = {
    val header = h.asInstanceOf[HeaderBuilderImpl]
    byteBuffer.clear()

    // Write fixed length parts
    byteBuffer.putInt(header.version)
    byteBuffer.putLong(header.uid)
    byteBuffer.putInt(header.serializer)

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

    // Serialize class manifest
    if (header._manifestIdx != -1)
      byteBuffer.putInt(ClassManifestTagOffset, header._manifestIdx | TagTypeMask)
    else
      writeLiteral(ClassManifestTagOffset, header._manifest)
  }

  def parseHeader(h: HeaderBuilder): Unit = {
    val header = h.asInstanceOf[HeaderBuilderImpl]

    // Read fixed length parts
    header.version = byteBuffer.getInt
    header.uid = byteBuffer.getLong
    header.serializer = byteBuffer.getInt

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

    // Deserialize class manifest
    val manifestTag = byteBuffer.getInt(ClassManifestTagOffset)
    if ((manifestTag & TagTypeMask) != 0) {
      val idx = manifestTag & TagValueMask
      header._manifest = null
      header._manifestIdx = idx
    } else {
      header._manifest = readLiteral()
    }
  }

  private def readLiteral(): String = {
    val length = byteBuffer.getShort
    if (length == 0)
      ""
    else {
      ensureLiteralCharsLength(length)
      val chars = literalChars
      val bytes = literalBytes
      byteBuffer.get(bytes, 0, length)
      var i = 0
      while (i < length) {
        // UsAscii
        chars(i) = bytes(i).asInstanceOf[Char]
        i += 1
      }
      String.valueOf(chars, 0, length)
    }
  }

  private def writeLiteral(tagOffset: Int, literal: String): Unit = {
    val length = literal.length
    if (length > 65535)
      throw new IllegalArgumentException("Literals longer than 65535 cannot be encoded in the envelope")

    byteBuffer.putInt(tagOffset, byteBuffer.position())

    if (length == 0) {
      byteBuffer.putShort(0)
    } else {
      byteBuffer.putShort(literal.length.toShort)
      ensureLiteralCharsLength(length)
      val bytes = literalBytes
      val chars = Unsafe.instance.getObject(literal, StringValueFieldOffset).asInstanceOf[Array[Char]]
      var i = 0
      while (i < length) {
        // UsAscii
        bytes(i) = chars(i).asInstanceOf[Byte]
        i += 1
      }
      byteBuffer.put(bytes, 0, length)
    }
  }

  private def ensureLiteralCharsLength(length: Int): Unit = {
    if (length > literalChars.length) {
      literalChars = Array.ofDim[Char](length)
      literalBytes = Array.ofDim[Byte](length)
    }
  }

  /**
   * DirectByteBuffers are garbage collected by using a phantom reference and a
   * reference queue. Every once a while, the JVM checks the reference queue and
   * cleans the DirectByteBuffers. However, as this doesn't happen
   * immediately after discarding all references to a DirectByteBuffer, it's
   * easy to OutOfMemoryError yourself using DirectByteBuffers. This function
   * explicitly calls the Cleaner method of a DirectByteBuffer.
   *
   * Utilizes reflection to avoid dependency to `sun.misc.Cleaner`.
   */
  def tryCleanDirectByteBuffer(): Unit = try {
    if (byteBuffer.isDirect) {
      val cleanerMethod = byteBuffer.getClass().getMethod("cleaner")
      cleanerMethod.setAccessible(true)
      val cleaner = cleanerMethod.invoke(byteBuffer)
      val cleanMethod = cleaner.getClass().getMethod("clean")
      cleanMethod.setAccessible(true)
      cleanMethod.invoke(cleaner)
    }
  } catch {
    case NonFatal(_) ⇒ // attempt failed, ok
  }

}
