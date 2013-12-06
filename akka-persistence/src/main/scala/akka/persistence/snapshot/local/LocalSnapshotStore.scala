/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.snapshot.local

import java.io._
import java.net.{ URLDecoder, URLEncoder }

import scala.collection.immutable.SortedSet
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
  private var snapshotMetadata = Map.empty[String, SortedSet[SnapshotMetadata]]

  def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future(load(processorId, criteria))(streamDispatcher)

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    Future(save(metadata, snapshot))(streamDispatcher)

  def saved(metadata: SnapshotMetadata) {
    snapshotMetadata = snapshotMetadata + (snapshotMetadata.get(metadata.processorId) match {
      case Some(mds) ⇒ metadata.processorId -> (mds + metadata)
      case None      ⇒ metadata.processorId -> SortedSet(metadata)
    })
  }

  def delete(metadata: SnapshotMetadata): Unit = {
    snapshotMetadata = snapshotMetadata.get(metadata.processorId) match {
      case Some(mds) ⇒ snapshotMetadata + (metadata.processorId -> (mds - metadata))
      case None      ⇒ snapshotMetadata
    }
    snapshotFile(metadata).delete()
  }

  def delete(processorId: String, criteria: SnapshotSelectionCriteria) = {
    snapshotMetadata.get(processorId) match {
      case Some(mds) ⇒ mds.filter(criteria.matches).foreach(delete)
      case None      ⇒
    }
  }

  private def load(processorId: String, criteria: SnapshotSelectionCriteria): Option[SelectedSnapshot] = {
    @scala.annotation.tailrec
    def load(metadata: SortedSet[SnapshotMetadata]): Option[SelectedSnapshot] = metadata.lastOption match {
      case None ⇒ None
      case Some(md) ⇒
        Try(withInputStream(md)(deserialize)) match {
          case Success(s) ⇒ Some(SelectedSnapshot(md, s.data))
          case Failure(e) ⇒
            log.error(e, s"error loading snapshot ${md}")
            load(metadata.init) // try older snapshot
        }
    }

    // Heuristics:
    //
    // Select youngest 3 snapshots that match upper bound. This may help in situations
    // where saving of a snapshot could not be completed because of a JVM crash. Hence,
    // an attempt to load that snapshot will fail but loading an older snapshot may
    // succeed.
    //
    // TODO: make number of loading attempts configurable

    for (md ← load(metadata(processorId).filter(criteria.matches).takeRight(3))) yield md
  }

  private def save(metadata: SnapshotMetadata, snapshot: Any): Unit =
    withOutputStream(metadata)(serialize(_, Snapshot(snapshot)))

  protected def deserialize(inputStream: InputStream): Snapshot =
    serializationExtension.deserialize(streamToBytes(inputStream), classOf[Snapshot]).get

  protected def serialize(outputStream: OutputStream, snapshot: Snapshot): Unit =
    outputStream.write(serializationExtension.findSerializerFor(snapshot).toBinary(snapshot))

  private def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) ⇒ Unit): Unit =
    withStream(new BufferedOutputStream(new FileOutputStream(snapshotFile(metadata))), p)

  private def withInputStream[T](metadata: SnapshotMetadata)(p: (InputStream) ⇒ T): T =
    withStream(new BufferedInputStream(new FileInputStream(snapshotFile(metadata))), p)

  private def withStream[A <: Closeable, B](stream: A, p: A ⇒ B): B =
    try { p(stream) } finally { stream.close() }

  private def snapshotFile(metadata: SnapshotMetadata): File =
    new File(snapshotDir, s"snapshot-${URLEncoder.encode(metadata.processorId, "UTF-8")}-${metadata.sequenceNr}-${metadata.timestamp}")

  private def metadata(processorId: String): SortedSet[SnapshotMetadata] =
    snapshotMetadata.getOrElse(processorId, SortedSet.empty)

  private def metadata: Seq[SnapshotMetadata] = snapshotDir.listFiles.map(_.getName).collect {
    case FilenamePattern(pid, snr, tms) ⇒ SnapshotMetadata(URLDecoder.decode(pid, "UTF-8"), snr.toLong, tms.toLong)
  }

  override def preStart() {
    if (!snapshotDir.exists) snapshotDir.mkdirs()
    snapshotMetadata = SortedSet.empty ++ metadata groupBy (_.processorId)
    super.preStart()
  }
}
