/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 * Copyright (C) 2012-2016 Eligotech BV.
 */

package akka.persistence.snapshot.local

import java.io._
import java.net.{ URLDecoder, URLEncoder }

import akka.actor.ActorLogging
import akka.persistence._
import akka.persistence.serialization._
import akka.persistence.snapshot._
import akka.serialization.SerializationExtension
import akka.util.ByteString.UTF_8

import scala.collection.immutable
import scala.concurrent.Future
import scala.util._

/**
 * INTERNAL API
 *
 * Local filesystem backed snapshot store.
 */
private[persistence] class LocalSnapshotStore extends SnapshotStore with ActorLogging {
  private val FilenamePattern = """^snapshot-(.+)-(\d+)-(\d+)""".r

  import akka.util.Helpers._
  private val config = context.system.settings.config.getConfig("akka.persistence.snapshot-store.local")
  private val maxLoadAttempts = config.getInt("max-load-attempts")
    .requiring(_ > 1, "max-load-attempts must be >= 1")

  private val streamDispatcher = context.system.dispatchers.lookup(config.getString("stream-dispatcher"))
  private val dir = new File(config.getString("dir"))

  private val serializationExtension = SerializationExtension(context.system)
  private var saving = immutable.Set.empty[SnapshotMetadata] // saving in progress

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    //
    // Heuristics:
    //
    // Select youngest `maxLoadAttempts` snapshots that match upper bound.
    // This may help in situations where saving of a snapshot could not be completed because of a JVM crash.
    // Hence, an attempt to load that snapshot will fail but loading an older snapshot may succeed.
    //
    val metadata = snapshotMetadatas(persistenceId, criteria).sorted.takeRight(maxLoadAttempts)
    Future(load(metadata))(streamDispatcher)
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    saving += metadata
    val completion = Future(save(metadata, snapshot))(streamDispatcher)
    completion
  }

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    saving -= metadata
    Future {
      // multiple snapshot files here mean that there were multiple snapshots for this seqNr, we delete all of them
      // usually snapshot-stores would keep one snapshot per sequenceNr however here in the file-based one we timestamp
      // snapshots and allow multiple to be kept around (for the same seqNr) if desired
      snapshotFiles(metadata).map(_.delete())
    }(streamDispatcher).map(_ ⇒ ())(streamDispatcher)
  }

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    val metadatas = snapshotMetadatas(persistenceId, criteria)
    Future.sequence {
      metadatas.map(deleteAsync)
    }(collection.breakOut, streamDispatcher).map(_ ⇒ ())(streamDispatcher)
  }

  override def receivePluginInternal: Receive = {
    case SaveSnapshotSuccess(metadata) ⇒ saving -= metadata
    case _: SaveSnapshotFailure        ⇒ // ignore
    case _: DeleteSnapshotsSuccess     ⇒ // ignore
    case _: DeleteSnapshotsFailure     ⇒ // ignore
  }

  private def snapshotFiles(metadata: SnapshotMetadata): immutable.Seq[File] = {
    snapshotDir.listFiles(new SnapshotSeqNrFilenameFilter(metadata)).toVector
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
    tmpFile.renameTo(snapshotFileForWrite(metadata))
  }

  protected def deserialize(inputStream: InputStream): Snapshot =
    serializationExtension.deserialize(streamToBytes(inputStream), classOf[Snapshot]).get

  protected def serialize(outputStream: OutputStream, snapshot: Snapshot): Unit =
    outputStream.write(serializationExtension.findSerializerFor(snapshot).toBinary(snapshot))

  protected def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) ⇒ Unit): File = {
    val tmpFile = snapshotFileForWrite(metadata, extension = "tmp")
    withStream(new BufferedOutputStream(new FileOutputStream(tmpFile)), p)
    tmpFile
  }

  private def withInputStream[T](metadata: SnapshotMetadata)(p: (InputStream) ⇒ T): T =
    withStream(new BufferedInputStream(new FileInputStream(snapshotFileForWrite(metadata))), p)

  private def withStream[A <: Closeable, B](stream: A, p: A ⇒ B): B =
    try { p(stream) } finally { stream.close() }

  /** Only by persistenceId and sequenceNr, timestamp is informational - accomodates for 2.13.x series files */
  private def snapshotFileForWrite(metadata: SnapshotMetadata, extension: String = ""): File =
    new File(snapshotDir, s"snapshot-${URLEncoder.encode(metadata.persistenceId, UTF_8)}-${metadata.sequenceNr}-${metadata.timestamp}${extension}")

  private def snapshotMetadatas(persistenceId: String, criteria: SnapshotSelectionCriteria): immutable.Seq[SnapshotMetadata] = {
    val files = snapshotDir.listFiles(new SnapshotFilenameFilter(persistenceId))
    if (files eq null) Nil // if the dir was removed
    else files.map(_.getName).collect {
      case FilenamePattern(pid, snr, tms) ⇒ SnapshotMetadata(URLDecoder.decode(pid, UTF_8), snr.toLong, tms.toLong)
    }.filter(md ⇒ criteria.matches(md) && !saving.contains(md)).toVector
  }

  override def preStart() {
    snapshotDir()
    super.preStart()
  }

  private def snapshotDir(): File = {
    if (!dir.isDirectory) {
      // try to create the directory, on failure double check if someone else beat us to it
      if (!dir.mkdirs() && !dir.isDirectory) {
        throw new IOException(s"Failed to create snapshot directory [${dir.getCanonicalPath}]")
      }
    }
    dir
  }

  private final class SnapshotFilenameFilter(persistenceId: String) extends FilenameFilter {
    def accept(dir: File, name: String): Boolean = {
      name match {
        case FilenamePattern(pid, snr, tms) ⇒ pid.equals(URLEncoder.encode(persistenceId))
        case _                              ⇒ false
      }
    }
  }

  private final class SnapshotSeqNrFilenameFilter(md: SnapshotMetadata) extends FilenameFilter {
    private final def matches(pid: String, snr: String, tms: String): Boolean = {
      pid.equals(URLEncoder.encode(md.persistenceId)) &&
        Try(snr.toLong == md.sequenceNr && (md.timestamp == 0L || tms.toLong == md.timestamp)).getOrElse(false)
    }

    def accept(dir: File, name: String): Boolean =
      name match {
        case FilenamePattern(pid, snr, tms) ⇒ matches(pid, snr, tms)
        case _                              ⇒ false
      }

  }
}
