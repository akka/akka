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

  // key/length of a metadata element are encoded within a single integer:
  final val EntryKeyMask = Integer.parseInt("1" * 4, 2) << 28
  def maskEntryKey(k: Byte): Int = (k.toInt << 28) & EntryKeyMask
  def unmaskEntryKey(kv: Int): Byte = ((kv & EntryKeyMask) >> 28).toByte

  final val EntryLengthMask = ~EntryKeyMask

  def maskEntryLength(k: Int): Int = k & EntryLengthMask
  def unmaskEntryLength(kv: Int): Int = kv & EntryLengthMask

  def muxEntryKeyLength(k: Byte, l: Int): Int =
    maskEntryKey(k) | maskEntryLength(l)

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
 *  |   Key   |               Metadata entry length                   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                   ... metadata entry ...                        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * }}}
 *
 */
private[akka] final class MetadataMapRendering(val metadataMap: MetadataMap[ByteString]) extends AnyVal {
  import MetadataEnvelopeSerializer._

  def render(): ByteString =
    if (metadataMap.isEmpty) ByteString.empty
    else {
      implicit val _ByteOrder = ByteOrder.LITTLE_ENDIAN

      val size = metadataMap.usedSlots * 4 /* bytes (Int) */ + metadataMap.foldLeftValues(0)(_ + _.length)
      val b = new ByteStringBuilder
      b.sizeHint(size)

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
private[akka] object MetadataMapRendering {
  import MetadataEnvelopeSerializer._

  /** Allocates an MetadataMap */
  def parse(headerBuilder: HeaderBuilder): MetadataMapRendering = {
    parseRaw(headerBuilder.metadataContainer)
  }

  /** Allocates an MetadataMap */
  def parseRaw(serializedMetadataContainer: ByteString): MetadataMapRendering = {
    val buf = serializedMetadataContainer.asByteBuffer
    buf.order(ByteOrder.LITTLE_ENDIAN)
    val totalLength = serializedMetadataContainer.length

    val map = MetadataMap[ByteString]()

    while (buf.position() < totalLength) {
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
  def applyAllRemoteMessageReceived(instruments: Vector[RemoteInstrument], decoded: InboundEnvelope, headerBuilder: HeaderBuilder): Unit = {
    val buf = headerBuilder.metadataContainer.asByteBuffer
    buf.order(ByteOrder.LITTLE_ENDIAN)

    while (buf.hasRemaining) {
      val keyAndLength = buf.getInt()
      val key = unmaskEntryKey(keyAndLength)
      val length = unmaskEntryLength(keyAndLength)

      val arr = Array.ofDim[Byte](length) // TODO can be optimised to re-cycle this array instead
      buf.get(arr)
      val data = ByteString(arr) // bytes

      instruments.find(_.identifier == key).getOrElse(throw new Exception(s"No RemoteInstrument for id $key available!"))
        .remoteMessageReceived(decoded.recipient, decoded.message, decoded.sender, data)
    }
  }
}
