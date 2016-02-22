/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 * Copyright (C) 2012-2016 Eligotech BV.
 */

package akka.persistence.serialization

import java.io._
import akka.actor._
import akka.serialization._
import akka.util.ByteString.UTF_8

/**
 * Wrapper for snapshot `data`. Snapshot `data` are the actual snapshot objects captured by
 * the persistent actor.
 *
 * @see [[SnapshotSerializer]]
 */
@SerialVersionUID(1L)
final case class Snapshot(data: Any)

/**
 * INTERNAL API.
 */
@SerialVersionUID(1L)
private[serialization] final case class SnapshotHeader(serializerId: Int, manifest: Option[String])

/**
 * [[Snapshot]] serializer.
 */
class SnapshotSerializer(val system: ExtendedActorSystem) extends BaseSerializer {

  override val includeManifest: Boolean = false

  private lazy val serialization = SerializationExtension(system)

  private lazy val transportInformation: Option[Serialization.Information] = {
    val address = system.provider.getDefaultAddress
    if (address.hasLocalScope) None
    else Some(Serialization.Information(address, system))
  }

  /**
   * Serializes a [[Snapshot]]. Delegates serialization of snapshot `data` to a matching
   * `akka.serialization.Serializer`.
   */
  def toBinary(o: AnyRef): Array[Byte] = o match {
    case Snapshot(data) ⇒ snapshotToBinary(data.asInstanceOf[AnyRef])
    case _              ⇒ throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  /**
   * Deserializes a [[Snapshot]]. Delegates deserialization of snapshot `data` to a matching
   * `akka.serialization.Serializer`.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef =
    Snapshot(snapshotFromBinary(bytes))

  private def snapshotToBinary(snapshot: AnyRef): Array[Byte] = {
    def serialize() = {
      val snapshotSerializer = serialization.findSerializerFor(snapshot)

      val headerOut = new ByteArrayOutputStream
      writeInt(headerOut, snapshotSerializer.identifier)

      snapshotSerializer match {
        case ser2: SerializerWithStringManifest ⇒
          val manifest = ser2.manifest(snapshot)
          if (manifest != "")
            headerOut.write(manifest.getBytes(UTF_8))
        case _ ⇒
          if (snapshotSerializer.includeManifest)
            headerOut.write(snapshot.getClass.getName.getBytes(UTF_8))
      }

      val headerBytes = headerOut.toByteArray

      val out = new ByteArrayOutputStream

      writeInt(out, headerBytes.length)

      out.write(headerBytes)
      out.write(snapshotSerializer.toBinary(snapshot))
      out.toByteArray
    }

    // serialize actor references with full address information (defaultAddress)
    transportInformation match {
      case Some(ti) ⇒ Serialization.currentTransportInformation.withValue(ti) { serialize() }
      case None     ⇒ serialize()
    }
  }

  private def snapshotFromBinary(bytes: Array[Byte]): AnyRef = {
    val in = new ByteArrayInputStream(bytes)
    val headerLength = readInt(in)
    val headerBytes = bytes.slice(4, headerLength + 4)
    val snapshotBytes = bytes.drop(headerLength + 4)

    /*
     * Attempt to find the `key` in the supplied array and if successful
     * construct a new array, replacing the contained UID with the `replacement`.
     */
    def patch(b: Array[Byte]): Array[Byte] = {
      import SnapshotSerializer._
      def find(pos: Int, offset: Int): Int = {
        if (pos == b.length) -1
        else if (offset == key.length) pos
        else if (b(pos + offset) == key(offset)) find(pos, offset + 1)
        else find(pos + 1, 0)
      }
      val found = find(0, 0)
      if (found == -1) b
      else {
        val n = new Array[Byte](b.length)
        val start = found + offset
        val end = start + replacement.length
        System.arraycopy(b, 0, n, 0, start)
        System.arraycopy(replacement, 0, n, start, replacement.length)
        System.arraycopy(b, end, n, end, b.length - end)
        n
      }
    }

    // If we are allowed to break serialization compatibility of stored snapshots in 2.4
    // we can remove this attempt to deserialize SnapshotHeader with JavaSerializer.
    // Then the class SnapshotHeader can be removed. See issue #16009
    val oldHeader =
      if (readShort(in) == 0xedac) { // Java Serialization magic value with swapped bytes
        val b = if (SnapshotSerializer.doPatch) patch(headerBytes) else headerBytes
        serialization.deserialize(b, classOf[SnapshotHeader]).toOption
      } else None

    val header = oldHeader.getOrElse {
      val headerIn = new ByteArrayInputStream(headerBytes)
      val serializerId = readInt(headerIn)
      val remaining = headerIn.available
      val manifest =
        if (remaining == 0) None
        else {
          val manifestBytes = Array.ofDim[Byte](remaining)
          headerIn.read(manifestBytes)
          Some(new String(manifestBytes, UTF_8))
        }
      SnapshotHeader(serializerId, manifest)
    }

    serialization.deserialize(snapshotBytes, header.serializerId, header.manifest.getOrElse("")).get
  }

  private def writeInt(outputStream: OutputStream, i: Int) =
    0 to 24 by 8 foreach { shift ⇒ outputStream.write(i >> shift) }

  private def readShort(inputStream: InputStream) = {
    val ch1 = inputStream.read()
    val ch2 = inputStream.read()
    (ch2 << 8) | ch1
  }

  private def readInt(inputStream: InputStream) = {
    val sh1 = readShort(inputStream)
    val sh2 = readShort(inputStream)
    (sh2 << 16) | sh1
  }
}

object SnapshotSerializer {
  /*
   * This is the serialized form of Class[Option[_]] (as a superclass, hence
   * the leading 0x78) with Scala 2.10.
   */
  val key: Array[Byte] = Array(
    0x78, 0x72, 0x00, 0x0c, 0x73, 0x63, 0x61, 0x6c,
    0x61, 0x2e, 0x4f, 0x70, 0x74, 0x69, 0x6f, 0x6e,
    0xe3, 0x60, 0x24, 0xa8, 0x32, 0x8a, 0x45, 0xe9, // here is the UID
    0x02, 0x00, 0x00, 0x78, 0x70).map(_.toByte)
  // The offset of the serialVersionUID in the above.
  val offset = 16
  // This is the new serialVersionUID from Scala 2.11 onwards.
  val replacement: Array[Byte] = Array(
    0xfe, 0x69, 0x37, 0xfd, 0xdb, 0x0e, 0x66, 0x74).map(_.toByte)
  val doPatch: Boolean = {
    /*
     * The only way to obtain the serialVersionUID for the Option[_] class is
     * to serialize one and look at the result, since prior to 2.11.5 it does
     * not declare such a field and relies on automatic UID generation.
     */
    val baus = new ByteArrayOutputStream()
    val out = new ObjectOutputStream(baus)
    out.writeObject(None)
    // the first 30 bytes serialize the None object, then comes its superclass Option[_]
    val superOffset = 30
    val uidOffset = superOffset + offset
    val clazz: Seq[Byte] = baus.toByteArray.slice(superOffset, uidOffset)
    val knownClazz: Seq[Byte] = key.take(offset)
    val uid: Seq[Byte] = baus.toByteArray.slice(uidOffset, uidOffset + replacement.length)
    // only attempt to patch if we know the target class
    if (clazz == knownClazz) {
      if (uid == replacement.toSeq) {
        // running on 2.11
        true
      } else if (uid == (key.slice(offset, offset + replacement.length): Seq[Byte])) {
        // running on 2.10, need to switch out UID between key and replacement
        val len = replacement.length
        val tmp = new Array[Byte](len)
        System.arraycopy(replacement, 0, tmp, 0, len)
        System.arraycopy(key, offset, replacement, 0, len)
        System.arraycopy(tmp, 0, key, offset, len)
        true
      } else false
    } else false
  }
}
