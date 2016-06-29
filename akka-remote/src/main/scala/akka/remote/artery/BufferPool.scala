/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.nio.charset.Charset
import java.nio.{ ByteBuffer, ByteOrder }

import akka.actor.{ ActorRef, Address }
import akka.remote.artery.compress.CompressionProtocol._
import akka.remote.artery.compress.{ CompressionTable, NoopInboundCompressions, NoopOutboundCompressions }
import akka.serialization.Serialization
import org.agrona.concurrent.{ ManyToManyConcurrentArrayQueue, UnsafeBuffer }
import akka.util.{ OptionVal, Unsafe }

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
  val ActorRefCompressionTableVersionTagOffset = 28 // Int
  val ClassManifestCompressionTableVersionTagOffset = 32 // Int

  val LiteralsSectionOffset = 36

  val UsAscii = Charset.forName("US-ASCII")

  val DeadLettersCode = 0

  // accessing the internal char array of String when writing literal strings to ByteBuffer
  val StringValueFieldOffset = Unsafe.instance.objectFieldOffset(classOf[String].getDeclaredField("value"))
}

/**
 * INTERNAL API
 * Decompress and cause compression advertisements.
 *
 * One per inbound message stream thus must demux by originUid to use the right tables.
 */
private[remote] trait InboundCompressions {
  def hitActorRef(originUid: Long, tableVersion: Int, remote: Address, ref: ActorRef): Unit
  def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef]

  def hitClassManifest(originUid: Long, tableVersion: Int, remote: Address, manifest: String): Unit
  def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String]
}
/**
 * INTERNAL API
 * Compress outgoing data and handle compression advertisements to fill compression table.
 *
 * One per outgoing message stream.
 */
private[remote] trait OutboundCompressions {
  def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit
  def compressActorRef(ref: ActorRef): Int

  def applyClassManifestCompressionTable(table: CompressionTable[String]): Unit
  def compressClassManifest(manifest: String): Int
}

/** INTERNAL API */
private[remote] object HeaderBuilder {

  // We really only use the Header builder on one "side" or the other, thus in order to avoid having to split its impl
  // we inject no-op compression's of the "other side".  

  def in(compression: InboundCompressions): HeaderBuilder = new HeaderBuilderImpl(compression, NoopOutboundCompressions)
  def out(compression: OutboundCompressions): HeaderBuilder = new HeaderBuilderImpl(NoopInboundCompressions, compression)

  /** INTERNAL API, FOR TESTING ONLY */
  private[remote] def bothWays(in: InboundCompressions, out: OutboundCompressions): HeaderBuilder = new HeaderBuilderImpl(in, out)
}

/**
 * INTERNAL API
 */
sealed trait HeaderBuilder {
  def setVersion(v: Int): Unit
  def version: Int

  def setActorRefCompressionTableVersion(v: Int): Unit
  def actorRefCompressionTableVersion: Int

  def setClassManifestCompressionTableVersion(v: Int): Unit
  def classManifestCompressionTableVersion: Int

  def setUid(u: Long): Unit
  def uid: Long

  def setSenderActorRef(ref: ActorRef): Unit
  /**
   * Retrive the compressed ActorRef by the compressionId carried by this header.
   * Returns `None` if ActorRef was not compressed, and then the literal [[senderActorRefPath]] should be used.
   */
  def senderActorRef(originUid: Long): OptionVal[ActorRef]
  /**
   * Retrive the raw literal actor path, instead of using the compressed value.
   * Returns `None` if ActorRef was compressed (!). To obtain the path in such case call [[senderActorRef]] and extract the path from it directly.
   */
  def senderActorRefPath: OptionVal[String]

  def setNoSender(): Unit
  def isNoSender: Boolean

  def setNoRecipient(): Unit
  def isNoRecipient: Boolean

  def setRecipientActorRef(ref: ActorRef): Unit
  /**
   * Retrive the compressed ActorRef by the compressionId carried by this header.
   * Returns `None` if ActorRef was not compressed, and then the literal [[recipientActorRefPath]] should be used.
   */
  def recipientActorRef(originUid: Long): OptionVal[ActorRef]
  /**
   * Retrive the raw literal actor path, instead of using the compressed value.
   * Returns `None` if ActorRef was compressed (!). To obtain the path in such case call [[recipientActorRefPath]] and extract the path from it directly.
   */
  def recipientActorRefPath: OptionVal[String]

  def setSerializer(serializer: Int): Unit
  def serializer: Int

  def setManifest(manifest: String): Unit
  def manifest(originUid: Long): String
}

/**
 * INTERNAL API
 */
private[remote] final class HeaderBuilderImpl(inboundCompression: InboundCompressions, outboundCompression: OutboundCompressions) extends HeaderBuilder {
  // Fields only available for EnvelopeBuffer
  var _version: Int = _
  var _uid: Long = _
  var _actorRefCompressionTableVersion: Int = -1
  var _classManifestCompressionTableVersion: Int = -1

  var _senderActorRef: String = null
  var _senderActorRefIdx: Int = -1
  var _recipientActorRef: String = null
  var _recipientActorRefIdx: Int = -1

  var _serializer: Int = _
  var _manifest: String = null
  var _manifestIdx: Int = -1

  override def setVersion(v: Int) = _version = v
  override def version = _version

  override def setUid(uid: Long) = _uid = uid
  override def uid: Long = _uid

  override def setActorRefCompressionTableVersion(v: Int): Unit = _actorRefCompressionTableVersion = v
  override def actorRefCompressionTableVersion: Int = _actorRefCompressionTableVersion

  override def setClassManifestCompressionTableVersion(v: Int): Unit = _classManifestCompressionTableVersion = v
  override def classManifestCompressionTableVersion: Int = _classManifestCompressionTableVersion

  override def setSenderActorRef(ref: ActorRef): Unit = {
    _senderActorRefIdx = outboundCompression.compressActorRef(ref)
    if (_senderActorRefIdx == -1) _senderActorRef = Serialization.serializedActorPath(ref) // includes local address from `currentTransportInformation`
  }
  override def setNoSender(): Unit = {
    _senderActorRef = null
    _senderActorRefIdx = EnvelopeBuffer.DeadLettersCode
  }
  override def isNoSender: Boolean =
    (_senderActorRef eq null) && _senderActorRefIdx == EnvelopeBuffer.DeadLettersCode
  override def senderActorRef(originUid: Long): OptionVal[ActorRef] =
    if (_senderActorRef eq null) inboundCompression.decompressActorRef(originUid, actorRefCompressionTableVersion, _senderActorRefIdx)
    else OptionVal.None
  def senderActorRefPath: OptionVal[String] =
    OptionVal(_senderActorRef)

  def setNoRecipient(): Unit = {
    _recipientActorRef = null
    _recipientActorRefIdx = EnvelopeBuffer.DeadLettersCode
  }
  def isNoRecipient: Boolean =
    (_recipientActorRef eq null) && _recipientActorRefIdx == EnvelopeBuffer.DeadLettersCode

  def setRecipientActorRef(ref: ActorRef): Unit = {
    _recipientActorRefIdx = outboundCompression.compressActorRef(ref)
    if (_recipientActorRefIdx == -1) _recipientActorRef = ref.path.toSerializationFormat
  }
  def recipientActorRef(originUid: Long): OptionVal[ActorRef] =
    if (_recipientActorRef eq null) inboundCompression.decompressActorRef(originUid, actorRefCompressionTableVersion, _recipientActorRefIdx)
    else OptionVal.None
  def recipientActorRefPath: OptionVal[String] =
    OptionVal(_recipientActorRef)

  override def setSerializer(serializer: Int): Unit = {
    _serializer = serializer
  }
  override def serializer: Int =
    _serializer

  override def setManifest(manifest: String): Unit = {
    _manifestIdx = outboundCompression.compressClassManifest(manifest)
    if (_manifestIdx == -1) _manifest = manifest
  }
  override def manifest(originUid: Long): String = {
    if (_manifest ne null) _manifest
    else {
      _manifest = inboundCompression.decompressClassManifest(originUid, classManifestCompressionTableVersion, _manifestIdx).get
      _manifest
    }
  }

  override def toString =
    "HeaderBuilderImpl(" +
      version + ", " +
      actorRefCompressionTableVersion + ", " +
      classManifestCompressionTableVersion + ", " +
      uid + ", " +
      _senderActorRef + ", " +
      _senderActorRefIdx + ", " +
      _recipientActorRef + ", " +
      _recipientActorRefIdx + ", " +
      _serializer + ", " +
      _manifest + ", " +
      _manifestIdx + ")"

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

    // compression table version numbers
    byteBuffer.putInt(ActorRefCompressionTableVersionTagOffset, header._actorRefCompressionTableVersion | TagTypeMask)
    byteBuffer.putInt(ClassManifestCompressionTableVersionTagOffset, header._classManifestCompressionTableVersion | TagTypeMask)

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
    header setVersion byteBuffer.getInt
    header setUid byteBuffer.getLong
    header setSerializer byteBuffer.getInt

    // compression table versions (stored in the Tag)
    val refCompressionVersionTag = byteBuffer.getInt(ActorRefCompressionTableVersionTagOffset)
    if ((refCompressionVersionTag & TagTypeMask) != 0) {
      header setActorRefCompressionTableVersion refCompressionVersionTag & TagValueMask
    }
    val manifestCompressionVersionTag = byteBuffer.getInt(ClassManifestCompressionTableVersionTagOffset)
    if ((manifestCompressionVersionTag & TagTypeMask) != 0) {
      header setClassManifestCompressionTableVersion manifestCompressionVersionTag & TagValueMask
    }

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
      val cleanerMethod = byteBuffer.getClass.getMethod("cleaner")
      cleanerMethod.setAccessible(true)
      val cleaner = cleanerMethod.invoke(byteBuffer)
      val cleanMethod = cleaner.getClass.getMethod("clean")
      cleanMethod.setAccessible(true)
      cleanMethod.invoke(cleaner)
    }
  } catch {
    case NonFatal(_) ⇒ // attempt failed, ok
  }

}
