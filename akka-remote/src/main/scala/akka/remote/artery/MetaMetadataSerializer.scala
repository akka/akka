/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.nio.ByteOrder

import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.util.ByteString.ByteString1C
import akka.util.{ ByteString, ByteStringBuilder, OptionVal }

/**
 * INTERNAL API
 *
 * MessageSerializer is a helper for serializing and deserialize messages
 */
private[akka] object MetaMetadataSerializer {

  // key/length of a metadata element are encoded within a single integer:
  final val EntryKeyMask = Integer.parseInt("1" * 4, 2) << 27
  def maskEntryKey(k: Byte): Int = (k << 27) & EntryKeyMask
  def unmaskEntryKey(kv: Int): Byte = ((kv & EntryKeyMask) >> 27).toByte

  final val EntryLengthMask = Integer.parseInt("1" * 27, 2)

  def maskEntryLength(k: Int): Int = k & EntryLengthMask
  def unmaskEntryLength(kv: Int): Int = kv & EntryLengthMask

  def muxEntryKeyLength(k: Byte, l: Int): Int =
    maskEntryKey(k) | maskEntryLength(l)

  def serializeForArtery(outboundEnvelope: OutboundEnvelope, headerBuilder: HeaderBuilder, envelope: EnvelopeBuffer): Unit = {
    outboundEnvelope.metadata match {
      case OptionVal.Some(m) ⇒ headerBuilder.setMetadata(new MetaMetadataEnvelope(m).render())
      case OptionVal.None    ⇒ headerBuilder.clearMetadata()
    }
  }

  def deserializeForArtery(system: ExtendedActorSystem, originUid: Long, serialization: Serialization,
                           serializer: Int, classManifest: String, envelope: EnvelopeBuffer): AnyRef = {
    serialization.deserializeByteBuffer(envelope.byteBuffer, serializer, classManifest)
  }
}

/**
 * {{{
 * Meta Metadata entry:
 *
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |           Total Length of metadata section (int)                |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                 ... N * metadata entries ...                    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * Metadata entry:
 *
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |   Key   |               Metadata entry length                   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                   ... metadata entry ...                        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * }}}
 *
 */
class MetaMetadataEnvelope(val m: Map[Byte, ByteString]) extends AnyVal {
  import MetaMetadataSerializer._

  def render(): ByteString =
    if (m.isEmpty) ByteString.empty
    else {
      implicit val _ByteOrder = ByteOrder.LITTLE_ENDIAN

      val size = m.size
      val b = new ByteStringBuilder
      b.sizeHint(4 + size * 4 + m.foldLeft(0)(_ + _._2.size))

      val ks = m.iterator
      b.putInt(size & EntryLengthMask)

      while (ks.hasNext) {
        val (key, value) = ks.next()
        if (0 <= key && key < 32)
          throw new IllegalArgumentException("Metadata key MUST be >= 0 and < 32. Was: " + key + ". " +
            "This is likely a configuration error. Each metadata attaching tool should use it's own, non-conflicting designated key. " +
            "The keys 0-7 are reserved for Akka internal use and future extensions.")

        b.putInt(maskEntryKey(key) | maskEntryLength(value.length))
        value match {
          case c: ByteString1C ⇒ c.appendToBuilder(b) // uses putByteArrayUnsafe
          case _               ⇒ b ++= value
        }
      }
      // format: ON

      b.result()
    }
}

object MetaMetadataEnvelope {
  def deserializeForArtery(metadata: ByteString1C): Map[Int, ByteString] = {
    val buf = metadata.asByteBuffer
    val mb = Map.newBuilder[Int, ByteString]

    val totalLength = metadata.length
    while (buf.position() < totalLength) {
      val key = buf.getInt() // k
      val len = buf.getInt() // l
      val arr = Array.ofDim[Byte](len)
      val data = ByteString(buf.get(arr)) // bytes
      mb += (key → data)
    }

    mb.result()
  }
}
