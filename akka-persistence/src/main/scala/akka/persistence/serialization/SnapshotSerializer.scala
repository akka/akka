/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.serialization

import java.io._

import akka.actor._
import akka.serialization.{ Serializer, SerializationExtension }

/**
 * Wrapper for snapshot `data`. Snapshot `data` are the actual snapshot objects captured by
 * a [[Processor]].
 *
 * @see [[SnapshotSerializer]]
 */
@SerialVersionUID(1L)
case class Snapshot(data: Any)

/**
 * INTERNAL API.
 */
@SerialVersionUID(1L)
private[serialization] case class SnapshotHeader(serializerId: Int, manifest: Option[String])

/**
 * [[Snapshot]] serializer.
 */
class SnapshotSerializer(system: ExtendedActorSystem) extends Serializer {
  def identifier: Int = 8
  def includeManifest: Boolean = false

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
    val extension = SerializationExtension(system)

    val snapshotSerializer = extension.findSerializerFor(snapshot)
    val snapshotManifest = if (snapshotSerializer.includeManifest) Some(snapshot.getClass.getName) else None

    val header = SnapshotHeader(snapshotSerializer.identifier, snapshotManifest)
    val headerSerializer = extension.findSerializerFor(header)
    val headerBytes = headerSerializer.toBinary(header)

    val out = new ByteArrayOutputStream

    writeInt(out, headerBytes.length)

    out.write(headerBytes)
    out.write(snapshotSerializer.toBinary(snapshot))
    out.toByteArray
  }

  private def snapshotFromBinary(bytes: Array[Byte]): AnyRef = {
    val extension = SerializationExtension(system)

    val headerLength = readInt(new ByteArrayInputStream(bytes))
    val headerBytes = bytes.slice(4, headerLength + 4)
    val snapshotBytes = bytes.drop(headerLength + 4)

    val header = extension.deserialize(headerBytes, classOf[SnapshotHeader]).get
    val manifest = header.manifest.map(system.dynamicAccess.getClassFor[AnyRef](_).get)

    extension.deserialize[AnyRef](snapshotBytes, header.serializerId, manifest).get
  }

  private def writeInt(outputStream: OutputStream, i: Int) =
    0 to 24 by 8 foreach { shift ⇒ outputStream.write(i >> shift) }

  private def readInt(inputStream: InputStream) =
    (0 to 24 by 8).foldLeft(0) { (id, shift) ⇒ (id | (inputStream.read() >> shift)) }
}
