/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.snapshot.local

import java.io._
import java.net.{ URLDecoder, URLEncoder }

import scala.collection.immutable
import scala.concurrent.Future
import scala.util._

import akka.actor.ActorLogging
import akka.persistence._
import akka.persistence.snapshot._
import akka.persistence.serialization._
import akka.serialization.SerializationExtension

/**
 * INTERNAL API.
 *
 * Local filesystem backed snapshot store.
 */
private[persistence] class LocalSnapshotStore extends SnapshotStore with ActorLogging {
  private val FilenamePattern = """^snapshot-(.+)-(\d+)-(\d+)""".r

  private val config = context.system.settings.config.getConfig("akka.persistence.snapshot-store.local")
  private val streamDispatcher = context.system.dispatchers.lookup(config.getString("stream-dispatcher"))
  private val snapshotDir = new File(config.getString("dir"))

  private val serializationExtension = SerializationExtension(context.system)
  private var saving = immutable.Set.empty[SnapshotMetadata] // saving in progress

  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    //
    // Heuristics:
    //
    // Select youngest 3 snapshots that match upper bound. This may help in situations
    // where saving of a snapshot could not be completed because of a JVM crash. Hence,
    // an attempt to load that snapshot will fail but loading an older snapshot may
    // succeed.
    //
    // TODO: make number of loading attempts configurable
    //
    val metadata = snapshotMetadata(persistenceId, criteria).sorted.takeRight(3)
    Future(load(metadata))(streamDispatcher)
  }

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    saving += metadata
    Future(save(metadata, snapshot))(streamDispatcher)
  }

  def saved(metadata: SnapshotMetadata): Unit = {
    saving -= metadata
  }

  def delete(metadata: SnapshotMetadata): Unit = {
    saving -= metadata
    snapshotFile(metadata).delete()
  }

  def delete(persistenceId: String, criteria: SnapshotSelectionCriteria) = {
    snapshotMetadata(persistenceId, criteria).foreach(delete)
  }

  @scala.annotation.tailrec
  private def load(metadata: immutable.Seq[SnapshotMetadata]): Option[SelectedSnapshot] = metadata.lastOption match {
    case None ⇒ None
    case Some(md) ⇒
      Try(withInputStream(md)(deserialize)) match {
        case Success(s) ⇒ Some(SelectedSnapshot(md, s.data))
        case Failure(e) ⇒
          log.error(e, s"Error loading snapshot [${md}]")
          load(metadata.init) // try older snapshot
      }
  }

  protected def save(metadata: SnapshotMetadata, snapshot: Any): Unit = {
    val tmpFile = withOutputStream(metadata)(serialize(_, Snapshot(snapshot)))
    tmpFile.renameTo(snapshotFile(metadata))
  }

  protected def deserialize(inputStream: InputStream): Snapshot =
    serializationExtension.deserialize(streamToBytes(inputStream), classOf[Snapshot]).get

  protected def serialize(outputStream: OutputStream, snapshot: Snapshot): Unit =
    outputStream.write(serializationExtension.findSerializerFor(snapshot).toBinary(snapshot))

  protected def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) ⇒ Unit): File = {
    val tmpFile = snapshotFile(metadata, extension = "tmp")
    withStream(new BufferedOutputStream(new FileOutputStream(tmpFile)), p)
    tmpFile
  }

  private def withInputStream[T](metadata: SnapshotMetadata)(p: (InputStream) ⇒ T): T =
    withStream(new BufferedInputStream(new FileInputStream(snapshotFile(metadata))), p)

  private def withStream[A <: Closeable, B](stream: A, p: A ⇒ B): B =
    try { p(stream) } finally { stream.close() }

  private def snapshotFile(metadata: SnapshotMetadata, extension: String = ""): File =
    new File(snapshotDir, s"snapshot-${URLEncoder.encode(metadata.persistenceId, "UTF-8")}-${metadata.sequenceNr}-${metadata.timestamp}${extension}")

  private def snapshotMetadata(persistenceId: String, criteria: SnapshotSelectionCriteria): immutable.Seq[SnapshotMetadata] =
    snapshotDir.listFiles(new SnapshotFilenameFilter(persistenceId)).map(_.getName).collect {
      case FilenamePattern(pid, snr, tms) ⇒ SnapshotMetadata(URLDecoder.decode(pid, "UTF-8"), snr.toLong, tms.toLong)
    }.filter(md ⇒ criteria.matches(md) && !saving.contains(md)).toVector

  override def preStart() {
    if (!snapshotDir.isDirectory) {
      // try to create the directory, on failure double check if someone else beat us to it
      if (!snapshotDir.mkdirs() && !snapshotDir.isDirectory) {
        throw new IOException(s"Failed to create snapshot directory [${snapshotDir.getCanonicalPath}]")
      }
    }
    super.preStart()
  }

  private class SnapshotFilenameFilter(persistenceId: String) extends FilenameFilter {
    def accept(dir: File, name: String): Boolean =
      name match {
        case FilenamePattern(pid, snr, tms) ⇒ pid.equals(URLEncoder.encode(persistenceId))
        case _                              ⇒ false
      }
  }
}
