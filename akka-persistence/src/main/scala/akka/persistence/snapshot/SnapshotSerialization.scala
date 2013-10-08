/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.snapshot

import java.io._

import akka.actor._
import akka.persistence.SnapshotMetadata
import akka.util.ClassLoaderObjectInputStream

/**
 * Snapshot serialization extension.
 */
private[persistence] object SnapshotSerialization extends ExtensionId[SnapshotSerialization] with ExtensionIdProvider {
  def createExtension(system: ExtendedActorSystem): SnapshotSerialization = new SnapshotSerialization(system)
  def lookup() = SnapshotSerialization
}

/**
 * Snapshot serialization extension.
 */
private[persistence] class SnapshotSerialization(val system: ExtendedActorSystem) extends Extension {
  import akka.serialization.JavaSerializer

  /**
   * Java serialization based snapshot serializer.
   */
  val java = new SnapshotSerializer {
    def serialize(stream: OutputStream, metadata: SnapshotMetadata, state: Any) = {
      val out = new ObjectOutputStream(stream)
      JavaSerializer.currentSystem.withValue(system) { out.writeObject(state) }
    }

    def deserialize(stream: InputStream, metadata: SnapshotMetadata) = {
      val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader, stream)
      JavaSerializer.currentSystem.withValue(system) { in.readObject }
    }
  }
}

/**
 * Stream-based snapshot serializer.
 */
private[persistence] trait SnapshotSerializer {
  /**
   * Serializes a `snapshot` to an output stream.
   */
  def serialize(stream: OutputStream, metadata: SnapshotMetadata, snapshot: Any): Unit

  /**
   * Deserializes a snapshot from an input stream.
   */
  def deserialize(stream: InputStream, metadata: SnapshotMetadata): Any
}

