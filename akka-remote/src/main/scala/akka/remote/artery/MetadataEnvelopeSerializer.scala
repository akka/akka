/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.nio.{ ByteBuffer, ByteOrder }

import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.util.ByteString.ByteString1C
import akka.util.{ ByteString, ByteStringBuilder }

/**
 * INTERNAL API
 */
private[akka] object MetadataEnvelopeSerializer {

  private[akka] val EmptyRendered = {
    implicit val _ByteOrder = ByteOrder.LITTLE_ENDIAN

    val bsb = new ByteStringBuilder
    bsb.putInt(0) // 0 length
    bsb.result
  }

  // key/length of a metadata element are encoded within a single integer:
  // supports keys in the range of <0-31>
  final val EntryKeyMask = Integer.parseInt("1111 1000 0000 0000 0000 0000 0000 000".replace(" ", ""), 2)
  def maskEntryKey(k: Byte): Int = (k.toInt << 26) & EntryKeyMask
  def unmaskEntryKey(kv: Int): Byte = ((kv & EntryKeyMask) >> 26).toByte

  final val EntryLengthMask = ~EntryKeyMask

  def maskEntryLength(k: Int): Int = k & EntryLengthMask
  def unmaskEntryLength(kv: Int): Int = kv & EntryLengthMask

  def muxEntryKeyLength(k: Byte, l: Int): Int = {
    maskEntryKey(k) | maskEntryLength(l)
  }

  def serialize(metadatas: MetadataMap[ByteString], headerBuilder: HeaderBuilder): Unit = {
    if (metadatas.isEmpty) headerBuilder.clearMetadataContainer()
    else {
      val container = new MetadataMapRendering(metadatas)
      headerBuilder.setMetadataContainer(container.render())
    }
  }

  def deserialize(system: ExtendedActorSystem, originUid: Long, serialization: Serialization,
                  serializer: Int, classManifest: String, envelope: EnvelopeBuffer): AnyRef = {
    serialization.deserializeByteBuffer(envelope.byteBuffer, serializer, classManifest)
  }
}

/**
 * INTERNAL API
 *
 * The metadata section is stored as ByteString (prefixed with Int length field,
 * the same way as any other literal), however the internal structure of it is as follows:
 *
 * {{{
 * Metadata entry:
 *
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |   Key     |             Metadata entry length                   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                   ... metadata entry ...                        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * }}}
 *
 */
private[akka] final class MetadataMapRendering(val metadataMap: MetadataMap[ByteString]) extends AnyVal {
  import MetadataEnvelopeSerializer._

  def render(): ByteString =
    if (metadataMap.isEmpty) {
      // usually no-one will want to render an empty metadata section - it should not be there at all
      EmptyRendered
    } else {
      implicit val _ByteOrder = ByteOrder.LITTLE_ENDIAN

      // TODO optimise this, we could just count along the way and then prefix with the length
      val totalSize = 4 /* length int field */ + metadataMap.usedSlots * 4 /* metadata length */ + metadataMap.foldLeftValues(0)(_ + _.length)
      val b = new ByteStringBuilder // TODO could we reuse one?
      b.sizeHint(totalSize)

      b.putInt(totalSize - 4 /* don't count itself, the length prefix */ )
      // TODO: move through and then prepend length
      metadataMap.foreach { (key: Byte, value: ByteString) ⇒
        // TODO try to remove allocation? Iterator otherwise, but that's also allocation
        val kl = muxEntryKeyLength(key, value.length)
        b.putInt(kl)
        value match {
          case c: ByteString1C ⇒ c.appendToBuilder(b) // uses putByteArrayUnsafe
          case _               ⇒ b ++= value
        }
      }
      b.result()
    }
}

/** INTERNAL API */
private[akka] object MetadataMapParsing {
  import MetadataEnvelopeSerializer._

  /** Allocates an MetadataMap */
  def parse(envelope: InboundEnvelope): MetadataMapRendering = {
    val buf = envelope.envelopeBuffer.byteBuffer
    buf.position(EnvelopeBuffer.MetadataContainerAndLiteralSectionOffset)
    parseRaw(buf)
  }

  /**
   * INTERNAL API, only for testing
   * The buffer MUST be already at the right position where the Metadata container starts.
   */
  private[akka] def parseRaw(buf: ByteBuffer) = {
    buf.order(ByteOrder.LITTLE_ENDIAN)
    val metadataContainerLength = buf.getInt()
    val endOfMetadataPos = metadataContainerLength + buf.position()
    val map = MetadataMap[ByteString]()

    while (buf.position() < endOfMetadataPos) {
      val kl = buf.getInt()
      val k = unmaskEntryKey(kl) // k
      val l = unmaskEntryLength(kl) // l

      val arr = Array.ofDim[Byte](l)
      buf.get(arr)
      val metadata = ByteString1C(arr) // avoids copying again
      map.set(k, metadata)
    }

    new MetadataMapRendering(map)
  }

  /** Implemented in a way to avoid allocations of any envelopes or arrays */
  def applyAllRemoteMessageReceived(instruments: Vector[RemoteInstrument], envelope: InboundEnvelope): Unit = {
    val buf = envelope.envelopeBuffer.byteBuffer
    buf.position(EnvelopeBuffer.MetadataContainerAndLiteralSectionOffset)
    applyAllRemoteMessageReceivedRaw(instruments, envelope, buf)
  }

  /**
   * INTERNAL API, only for testing
   * The buffer MUST be already at the right position where the Metadata container starts.
   */
  private[akka] def applyAllRemoteMessageReceivedRaw(instruments: Vector[RemoteInstrument], envelope: InboundEnvelope, buf: ByteBuffer): Unit = {
    buf.order(ByteOrder.LITTLE_ENDIAN)

    val metadataContainerLength = buf.getInt()
    val endOfMetadataPos = metadataContainerLength + buf.position()

    while (buf.position() < endOfMetadataPos) {
      val keyAndLength = buf.getInt()
      val key = unmaskEntryKey(keyAndLength)
      val length = unmaskEntryLength(keyAndLength)

      val arr = Array.ofDim[Byte](length) // TODO can be optimised to re-cycle this array instead
      buf.get(arr)
      val data = ByteString(arr) // bytes

      instruments.find(_.identifier == key) match {
        case Some(instr) ⇒ instr.remoteMessageReceived(envelope.recipient.orNull, envelope.message, envelope.sender.orNull, data)
        case _           ⇒ throw new Exception(s"No RemoteInstrument for id $key available!")

      }
    }
  }
}
