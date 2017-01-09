/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.nio.charset.Charset
import java.nio.{ ByteBuffer, ByteOrder }

import akka.actor.{ ActorPath, ActorRef, Address, ChildActorPath }
import akka.io.DirectByteBufferPool
import akka.remote.artery.compress.CompressionProtocol._
import akka.remote.artery.compress.{ CompressionTable, InboundCompressions }
import akka.serialization.Serialization
import org.agrona.concurrent.{ ManyToManyConcurrentArrayQueue, UnsafeBuffer }
import akka.util.{ ByteString, CompactByteString, OptionVal, Unsafe }

import akka.remote.artery.compress.NoInboundCompressions
import akka.util.ByteString.ByteString1C

import scala.annotation.tailrec

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

/** INTERNAL API */
private[remote] final class ByteFlag(val mask: Byte) extends AnyVal {
  def isEnabled(byteFlags: Byte): Boolean = (byteFlags.toInt & mask) != 0
  override def toString = s"ByteFlag(${ByteFlag.binaryLeftPad(mask)})"
}
/**
 * INTERNAL API
 */
private[remote] object ByteFlag {
  def binaryLeftPad(byte: Byte): String = {
    val string = Integer.toBinaryString(byte)
    val pad = "0" * (8 - string.length) // leftPad
    pad + string
  }
}

/**
 * INTERNAL API
 */
private[remote] object EnvelopeBuffer {

  val TagTypeMask = 0xFF000000
  val TagValueMask = 0x0000FFFF

  // Flags (1 byte allocated for them)
  val MetadataPresentFlag = new ByteFlag(0x1)

  val VersionOffset = 0 // Byte
  val FlagsOffset = 1 // Byte
  val ActorRefCompressionTableVersionOffset = 2 // Byte
  val ClassManifestCompressionTableVersionOffset = 3 // Byte

  val UidOffset = 4 // Long
  val SerializerOffset = 12 // Int

  val SenderActorRefTagOffset = 16 // Int
  val RecipientActorRefTagOffset = 20 // Int
  val ClassManifestTagOffset = 24 // Int

  // EITHER metadata followed by literals directly OR literals directly in this spot.
  // Mode depends on the `MetadataPresentFlag`.
  val MetadataContainerAndLiteralSectionOffset = 28 // Int

  val UsAscii = Charset.forName("US-ASCII")

  // accessing the internal char array of String when writing literal strings to ByteBuffer
  val StringValueFieldOffset = Unsafe.instance.objectFieldOffset(classOf[String].getDeclaredField("value"))
}

/** INTERNAL API */
private[remote] object HeaderBuilder {

  // We really only use the Header builder on one "side" or the other, thus in order to avoid having to split its impl
  // we inject no-op compression's of the "other side".

  def in(compression: InboundCompressions): HeaderBuilder =
    new HeaderBuilderImpl(compression, CompressionTable.empty[ActorRef], CompressionTable.empty[String])

  def out(): HeaderBuilder =
    new HeaderBuilderImpl(NoInboundCompressions, CompressionTable.empty[ActorRef], CompressionTable.empty[String])

  final val DeadLettersCode = -1
}

/**
 * INTERNAL API
 */
private[remote] sealed trait HeaderBuilder {
  def setVersion(v: Byte): Unit
  def version: Byte

  def setFlags(v: Byte): Unit
  def flags: Byte
  def flag(byteFlag: ByteFlag): Boolean
  def setFlag(byteFlag: ByteFlag, value: Boolean): Unit

  def inboundActorRefCompressionTableVersion: Byte
  def inboundClassManifestCompressionTableVersion: Byte

  def useOutboundCompression(on: Boolean): Unit

  def outboundActorRefCompression: CompressionTable[ActorRef]
  def setOutboundActorRefCompression(table: CompressionTable[ActorRef]): Unit

  def outboundClassManifestCompression: CompressionTable[String]
  def setOutboundClassManifestCompression(table: CompressionTable[String]): Unit

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
  def manifest(originUid: Long): OptionVal[String]

  def setRemoteInstruments(instruments: RemoteInstruments): Unit

  /**
   * Reset all fields that are related to an outbound message,
   * i.e. Encoder calls this as the first thing in onPush.
   */
  def resetMessageFields(): Unit
}

/**
 * INTERNAL API
 */
private[remote] final class SerializationFormatCache
  extends LruBoundedCache[ActorRef, String](capacity = 1024, evictAgeThreshold = 600) {

  override protected def compute(ref: ActorRef): String = ref.path.toSerializationFormat

  // Not calling ref.hashCode since it does a path.hashCode if ActorCell.undefinedUid is encountered.
  // Refs with ActorCell.undefinedUid will now collide all the time, but this is not a usual scenario anyway.
  override protected def hash(ref: ActorRef): Int = ref.path.uid

  override protected def isCacheable(v: String): Boolean = true
}

/**
 * INTERNAL API
 */
private[remote] final class HeaderBuilderImpl(
  inboundCompression:                    InboundCompressions,
  var _outboundActorRefCompression:      CompressionTable[ActorRef],
  var _outboundClassManifestCompression: CompressionTable[String]) extends HeaderBuilder {
  import HeaderBuilder.DeadLettersCode

  private[this] val toSerializationFormat: SerializationFormatCache = new SerializationFormatCache

  // Fields only available for EnvelopeBuffer
  var _version: Byte = 0
  var _flags: Byte = 0
  var _uid: Long = 0
  var _inboundActorRefCompressionTableVersion: Byte = 0
  var _inboundClassManifestCompressionTableVersion: Byte = 0
  var _useOutboundCompression: Boolean = true

  var _senderActorRef: String = null
  var _senderActorRefIdx: Int = -1
  var _recipientActorRef: String = null
  var _recipientActorRefIdx: Int = -1

  var _serializer: Int = 0
  var _manifest: String = null
  var _manifestIdx: Int = -1

  var _remoteInstruments: OptionVal[RemoteInstruments] = OptionVal.None

  override def resetMessageFields(): Unit = {
    _flags = 0
    _senderActorRef = null
    _senderActorRefIdx = -1
    _recipientActorRef = null
    _recipientActorRefIdx = -1

    _serializer = 0
    _manifest = null
    _manifestIdx = -1

    _remoteInstruments = OptionVal.None
  }

  override def setVersion(v: Byte) = _version = v
  override def version = _version

  override def setFlags(v: Byte) = _flags = v
  override def flags = _flags
  override def flag(byteFlag: ByteFlag): Boolean = (_flags.toInt & byteFlag.mask) != 0
  override def setFlag(byteFlag: ByteFlag, value: Boolean): Unit =
    if (value) _flags = (flags | byteFlag.mask).toByte
    else _flags = (flags & ~byteFlag.mask).toByte

  override def setUid(uid: Long) = _uid = uid
  override def uid: Long = _uid

  override def inboundActorRefCompressionTableVersion: Byte = _inboundActorRefCompressionTableVersion
  override def inboundClassManifestCompressionTableVersion: Byte = _inboundClassManifestCompressionTableVersion

  def useOutboundCompression(on: Boolean): Unit =
    _useOutboundCompression = on

  def setOutboundActorRefCompression(table: CompressionTable[ActorRef]): Unit = {
    _outboundActorRefCompression = table
  }
  override def outboundActorRefCompression: CompressionTable[ActorRef] = _outboundActorRefCompression

  def setOutboundClassManifestCompression(table: CompressionTable[String]): Unit = {
    _outboundClassManifestCompression = table
  }
  def outboundClassManifestCompression: CompressionTable[String] = _outboundClassManifestCompression

  override def setSenderActorRef(ref: ActorRef): Unit = {
    // serializedActorPath includes local address from `currentTransportInformation`
    if (_useOutboundCompression) {
      _senderActorRefIdx = outboundActorRefCompression.compress(ref)
      if (_senderActorRefIdx == -1) _senderActorRef = Serialization.serializedActorPath(ref)
    } else
      _senderActorRef = Serialization.serializedActorPath(ref)
  }
  override def setNoSender(): Unit = {
    _senderActorRef = null
    _senderActorRefIdx = DeadLettersCode
  }
  override def isNoSender: Boolean =
    (_senderActorRef eq null) && _senderActorRefIdx == DeadLettersCode
  override def senderActorRef(originUid: Long): OptionVal[ActorRef] = {
    // we treat deadLetters as always present, but not included in table
    if ((_senderActorRef eq null) && !isNoSender)
      inboundCompression.decompressActorRef(originUid, inboundActorRefCompressionTableVersion, _senderActorRefIdx)
    else OptionVal.None
  }

  def senderActorRefPath: OptionVal[String] =
    OptionVal(_senderActorRef)

  def setNoRecipient(): Unit = {
    _recipientActorRef = null
    _recipientActorRefIdx = DeadLettersCode
  }
  def isNoRecipient: Boolean =
    (_recipientActorRef eq null) && _recipientActorRefIdx == DeadLettersCode

  def setRecipientActorRef(ref: ActorRef): Unit = {
    if (_useOutboundCompression) {
      _recipientActorRefIdx = outboundActorRefCompression.compress(ref)
      if (_recipientActorRefIdx == -1) _recipientActorRef = toSerializationFormat.getOrCompute(ref)
    } else
      _recipientActorRef = toSerializationFormat.getOrCompute(ref)
  }
  def recipientActorRef(originUid: Long): OptionVal[ActorRef] = {
    // we treat deadLetters as always present, but not included in table
    if ((_recipientActorRef eq null) && !isNoRecipient)
      inboundCompression.decompressActorRef(originUid, inboundActorRefCompressionTableVersion, _recipientActorRefIdx)
    else OptionVal.None
  }
  def recipientActorRefPath: OptionVal[String] =
    OptionVal(_recipientActorRef)

  override def setSerializer(serializer: Int): Unit = {
    _serializer = serializer
  }
  override def serializer: Int =
    _serializer

  override def setManifest(manifest: String): Unit = {
    if (_useOutboundCompression) {
      _manifestIdx = outboundClassManifestCompression.compress(manifest)
      if (_manifestIdx == -1) _manifest = manifest
    } else
      _manifest = manifest
  }
  override def manifest(originUid: Long): OptionVal[String] = {
    if (_manifest ne null) OptionVal.Some(_manifest)
    else {
      inboundCompression.decompressClassManifest(
        originUid,
        inboundClassManifestCompressionTableVersion, _manifestIdx)
    }
  }

  override def setRemoteInstruments(instruments: RemoteInstruments): Unit = {
    _remoteInstruments = OptionVal(instruments)
  }

  override def toString =
    "HeaderBuilderImpl(" +
      "version:" + version + ", " +
      "flags:" + ByteFlag.binaryLeftPad(flags) + ", " +
      "UID:" + uid + ", " +
      "_senderActorRef:" + _senderActorRef + ", " +
      "_senderActorRefIdx:" + _senderActorRefIdx + ", " +
      "_recipientActorRef:" + _recipientActorRef + ", " +
      "_recipientActorRefIdx:" + _recipientActorRefIdx + ", " +
      "_serializer:" + _serializer + ", " +
      "_manifest:" + _manifest + ", " +
      "_manifestIdx:" + _manifestIdx + ")"

}

/**
 * INTERNAL API
 */
private[remote] final class EnvelopeBuffer(val byteBuffer: ByteBuffer) {
  import EnvelopeBuffer._
  val aeronBuffer = new UnsafeBuffer(byteBuffer)

  private var literalChars = Array.ofDim[Char](64)
  private var literalBytes = Array.ofDim[Byte](64)

  def writeHeader(h: HeaderBuilder): Unit = writeHeader(h, null)

  def writeHeader(h: HeaderBuilder, oe: OutboundEnvelope): Unit = {
    val header = h.asInstanceOf[HeaderBuilderImpl]
    byteBuffer.clear()

    // Write fixed length parts
    byteBuffer.put(VersionOffset, header.version)
    byteBuffer.put(FlagsOffset, header.flags)
    byteBuffer.putLong(UidOffset, header.uid)
    byteBuffer.putInt(SerializerOffset, header.serializer)

    // compression table version numbers
    byteBuffer.put(ActorRefCompressionTableVersionOffset, header.outboundActorRefCompression.version)
    byteBuffer.put(ClassManifestCompressionTableVersionOffset, header.outboundClassManifestCompression.version)

    // maybe write some metadata
    // after metadata is written (or not), buffer is at correct position to continue writing literals
    byteBuffer.position(MetadataContainerAndLiteralSectionOffset)
    if (header._remoteInstruments.isDefined) {
      header._remoteInstruments.get.serialize(OptionVal(oe), byteBuffer)
      if (byteBuffer.position() != MetadataContainerAndLiteralSectionOffset) {
        // we actually wrote some metadata so update the flag field to reflect that
        header.setFlag(MetadataPresentFlag, true)
        byteBuffer.put(FlagsOffset, header.flags)
      }
    }

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
    header.setVersion(byteBuffer.get(VersionOffset))
    header.setFlags(byteBuffer.get(FlagsOffset))
    header.setUid(byteBuffer.getLong(UidOffset))
    header.setSerializer(byteBuffer.getInt(SerializerOffset))

    // compression table versions (stored in the Tag)
    header._inboundActorRefCompressionTableVersion = byteBuffer.get(ActorRefCompressionTableVersionOffset)
    header._inboundClassManifestCompressionTableVersion = byteBuffer.get(ClassManifestCompressionTableVersionOffset)

    byteBuffer.position(MetadataContainerAndLiteralSectionOffset)
    if (header.flag(MetadataPresentFlag)) {
      // metadata present, so we need to fast forward to the literals that start right after
      val totalMetadataLength = byteBuffer.getInt()
      byteBuffer.position(byteBuffer.position() + totalMetadataLength)
    }

    // Deserialize sender
    val senderTag = byteBuffer.getInt(SenderActorRefTagOffset)
    if ((senderTag & TagTypeMask) != 0) {
      val idx = senderTag & TagValueMask
      header._senderActorRef = null
      header._senderActorRefIdx = idx
    } else {
      header._senderActorRef = emptyAsNull(readLiteral())
    }

    // Deserialize recipient
    val recipientTag = byteBuffer.getInt(RecipientActorRefTagOffset)
    if ((recipientTag & TagTypeMask) != 0) {
      val idx = recipientTag & TagValueMask
      header._recipientActorRef = null
      header._recipientActorRefIdx = idx
    } else {
      header._recipientActorRef = emptyAsNull(readLiteral())
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

  private def emptyAsNull(s: String): String =
    if (s == "") null
    else s

  private def readLiteral(): String = {
    val length = byteBuffer.getShort
    if (length == 0) ""
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
    val length = if (literal eq null) 0 else literal.length
    if (length > 65535)
      throw new IllegalArgumentException("Literals longer than 65535 cannot be encoded in the envelope")

    byteBuffer.putInt(tagOffset, byteBuffer.position())

    if (length == 0) {
      byteBuffer.putShort(0)
    } else {
      byteBuffer.putShort(length.toShort)
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

  def tryCleanDirectByteBuffer(): Unit = DirectByteBufferPool.tryCleanDirectByteBuffer(byteBuffer)
}
