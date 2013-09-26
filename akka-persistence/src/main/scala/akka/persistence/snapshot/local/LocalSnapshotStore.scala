/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.snapshot.local

import java.io._
import java.net.{ URLDecoder, URLEncoder }

import scala.collection.SortedSet
import scala.concurrent._
import scala.util._

import com.typesafe.config.Config

import akka.actor._
import akka.persistence._

/**
 * [[LocalSnapshotStore]] settings.
 */
private[persistence] class LocalSnapshotStoreSettings(config: Config) extends SnapshotStoreFactory {
  /**
   * Name of directory where snapshot files shall be stored.
   */
  val snapshotDir: File = new File(config.getString("dir"))

  /**
   * Creates a new snapshot store actor.
   */
  def createSnapshotStore(implicit factory: ActorRefFactory): ActorRef =
    factory.actorOf(Props(classOf[LocalSnapshotStore], this))
}
/**
 * Snapshot store that stores snapshots on local filesystem.
 */
private[persistence] class LocalSnapshotStore(settings: LocalSnapshotStoreSettings) extends Actor with ActorLogging {
  private implicit val executionContext = context.system.dispatchers.lookup("akka.persistence.snapshot-store.local.io.dispatcher")

  // TODO: make snapshot access configurable
  // TODO: make snapshot serializer configurable

  private val snapshotDir = settings.snapshotDir
  private val snapshotAccess = new LocalSnapshotAccess(snapshotDir)
  private val snapshotSerializer = SnapshotSerialization(context.system).java

  var snapshotMetadata = Map.empty[String, SortedSet[SnapshotMetadata]]

  import SnapshotStore._

  def receive = {
    case LoadSnapshot(processorId, criteria, toSequenceNr) ⇒ {
      val p = sender
      loadSnapshotAsync(processorId, criteria.limit(toSequenceNr)) onComplete {
        case Success(sso) ⇒ p ! LoadSnapshotCompleted(sso, toSequenceNr)
        case Failure(_)   ⇒ p ! LoadSnapshotCompleted(None, toSequenceNr)
      }
    }
    case SaveSnapshot(metadata, snapshot) ⇒ {
      val p = sender
      val md = metadata.copy(timestamp = System.currentTimeMillis)
      saveSnapshotAsync(md, snapshot) onComplete {
        case Success(_) ⇒ self tell (SaveSnapshotSucceeded(md), p)
        case Failure(e) ⇒ self tell (SaveSnapshotFailed(metadata, e), p)
      }
    }
    case evt @ SaveSnapshotSucceeded(metadata) ⇒ {
      updateMetadata(metadata)
      sender ! evt // sender is processor
    }
    case evt @ SaveSnapshotFailed(metadata, reason) ⇒ {
      deleteSnapshot(metadata)
      sender ! evt // sender is processor
    }
  }

  def loadSnapshotAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SavedSnapshot]] =
    Future(loadSnapshot(processorId, criteria))

  def loadSnapshot(processorId: String, criteria: SnapshotSelectionCriteria): Option[SavedSnapshot] = {
    @scala.annotation.tailrec
    def load(metadata: SortedSet[SnapshotMetadata]): Option[SavedSnapshot] = metadata.lastOption match {
      case None ⇒ None
      case Some(md) ⇒ {
        Try(snapshotAccess.withInputStream(md)(snapshotSerializer.deserialize(_, md))) match {
          case Success(ss) ⇒ Some(SavedSnapshot(md, ss))
          case Failure(e) ⇒ {
            log.error(e, s"error loading snapshot ${md}")
            load(metadata.init) // try older snapshot
          }
        }
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
    // TODO: improve heuristics for remote snapshot loading

    for {
      mds ← snapshotMetadata.get(processorId)
      md ← load(mds.filter(md ⇒
        md.sequenceNr <= criteria.maxSequenceNr &&
          md.timestamp <= criteria.maxTimestamp).takeRight(3))
    } yield md
  }

  def saveSnapshotAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    Future(saveSnapshot(metadata, snapshot))

  private def saveSnapshot(metadata: SnapshotMetadata, snapshot: Any): Unit =
    snapshotAccess.withOutputStream(metadata)(snapshotSerializer.serialize(_, metadata, snapshot))

  def deleteSnapshot(metadata: SnapshotMetadata): Unit =
    snapshotAccess.delete(metadata)

  def updateMetadata(metadata: SnapshotMetadata): Unit = {
    snapshotMetadata = snapshotMetadata + (snapshotMetadata.get(metadata.processorId) match {
      case Some(mds) ⇒ metadata.processorId -> (mds + metadata)
      case None      ⇒ metadata.processorId -> SortedSet(metadata)
    })
  }

  override def preStart() {
    if (!snapshotDir.exists) snapshotDir.mkdirs()
    snapshotMetadata = SortedSet.empty ++ snapshotAccess.metadata groupBy (_.processorId)
    super.preStart()
  }
}

/**
 * Access to snapshot files on local filesystem.
 */
private[persistence] class LocalSnapshotAccess(snapshotDir: File) extends SnapshotAccess {
  private val FilenamePattern = """^snapshot-(.+)-(\d+)-(\d+)""".r

  def metadata: Set[SnapshotMetadata] = snapshotDir.listFiles.map(_.getName).collect {
    case FilenamePattern(pid, snr, tms) ⇒ SnapshotMetadata(URLDecoder.decode(pid, "UTF-8"), snr.toLong, tms.toLong)
  }.toSet

  def delete(metadata: SnapshotMetadata): Unit =
    snapshotFile(metadata).delete()

  def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) ⇒ Unit) =
    withStream(new BufferedOutputStream(new FileOutputStream(snapshotFile(metadata))), p)

  def withInputStream(metadata: SnapshotMetadata)(p: (InputStream) ⇒ Any) =
    withStream(new BufferedInputStream(new FileInputStream(snapshotFile(metadata))), p)

  private def withStream[A <: Closeable, B](stream: A, p: A ⇒ B): B =
    try { p(stream) } finally { stream.close() }

  private def snapshotFile(metadata: SnapshotMetadata): File =
    new File(snapshotDir, s"snapshot-${URLEncoder.encode(metadata.processorId, "UTF-8")}-${metadata.sequenceNr}-${metadata.timestamp}")
}
