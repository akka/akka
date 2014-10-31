/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.serialization

import java.io._
import akka.actor._
import akka.serialization.{ Serializer, SerializationExtension }
import akka.serialization.Serialization
import scala.util.Success
import scala.util.Failure

/**
 * Wrapper for snapshot `data`. Snapshot `data` are the actual snapshot objects captured by
 * a [[Processor]].
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
class SnapshotSerializer(system: ExtendedActorSystem) extends Serializer {
  def identifier: Int = 8
  def includeManifest: Boolean = false

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
      val extension = SerializationExtension(system)

      val snapshotSerializer = extension.findSerializerFor(snapshot)

      val headerOut = new ByteArrayOutputStream
      writeInt(headerOut, snapshotSerializer.identifier)
      if (snapshotSerializer.includeManifest)
        headerOut.write(snapshot.getClass.getName.getBytes("utf-8"))
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
    val extension = SerializationExtension(system)

    val headerLength = readInt(new ByteArrayInputStream(bytes))
    val headerBytes = bytes.slice(4, headerLength + 4)
    val snapshotBytes = bytes.drop(headerLength + 4)

    // If we are allowed to break serialization compatibility of stored snapshots in 2.4
    // we can remove this attempt of deserialize SnapshotHeader with JavaSerializer.
    // Then the class SnapshotHeader can be removed. See isseue #16009
    val header = extension.deserialize(headerBytes, classOf[SnapshotHeader]) match {
      case Success(header) ⇒
        header
      case Failure(_) ⇒
        val headerIn = new ByteArrayInputStream(headerBytes)
        val serializerId = readInt(headerIn)
        val remaining = headerIn.available
        val manifest =
          if (remaining == 0) None
          else {
            val manifestBytes = Array.ofDim[Byte](remaining)
            headerIn.read(manifestBytes)
            Some(new String(manifestBytes, "utf-8"))
          }
        SnapshotHeader(serializerId, manifest)
    }
    val manifest = header.manifest.map(system.dynamicAccess.getClassFor[AnyRef](_).get)

    extension.deserialize[AnyRef](snapshotBytes, header.serializerId, manifest).get
  }

  private def writeInt(outputStream: OutputStream, i: Int) =
    0 to 24 by 8 foreach { shift ⇒ outputStream.write(i >> shift) }

  private def readInt(inputStream: InputStream) =
    (0 to 24 by 8).foldLeft(0) { (id, shift) ⇒ (id | (inputStream.read() << shift)) }
}
