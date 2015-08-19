/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import java.io.File
import scala.collection.mutable
import akka.actor._
import akka.persistence._
import akka.persistence.journal.{ WriteJournalBase, AsyncWriteTarget }
import akka.serialization.SerializationExtension
import org.iq80.leveldb._
import scala.collection.immutable
import scala.util._
import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.persistence.journal.AsyncWriteJournal

/**
 * INTERNAL API.
 */
private[persistence] trait LeveldbStore extends Actor with WriteJournalBase with LeveldbIdMapping with LeveldbRecovery {
  val configPath: String

  val config = context.system.settings.config.getConfig(configPath)
  val nativeLeveldb = config.getBoolean("native")

  val leveldbOptions = new Options().createIfMissing(true)
  def leveldbReadOptions = new ReadOptions().verifyChecksums(config.getBoolean("checksum"))
  val leveldbWriteOptions = new WriteOptions().sync(config.getBoolean("fsync")).snapshot(false)
  val leveldbDir = new File(config.getString("dir"))
  var leveldb: DB = _

  private val persistenceIdSubscribers = new mutable.HashMap[String, mutable.Set[ActorRef]] with mutable.MultiMap[String, ActorRef]
  private var allPersistenceIdsSubscribers = Set.empty[ActorRef]

  def leveldbFactory =
    if (nativeLeveldb) org.fusesource.leveldbjni.JniDBFactory.factory
    else org.iq80.leveldb.impl.Iq80DBFactory.factory

  val serialization = SerializationExtension(context.system)

  import Key._

  def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    var persistenceIds = Set.empty[String]
    val hasSubscribers = hasPersistenceIdSubscribers
    val result = Future.fromTry(Try {
      withBatch(batch ⇒ messages.map { a ⇒
        Try {
          a.payload.foreach(message ⇒ addToMessageBatch(message, batch))
          if (hasSubscribers)
            persistenceIds += a.persistenceId
        }
      })
    })

    if (hasSubscribers) {
      persistenceIds.foreach { pid ⇒
        notifyPersistenceIdChange(pid)
      }
    }
    result
  }

  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    try Future.successful {
      withBatch { batch ⇒
        val nid = numericId(persistenceId)

        // seek to first existing message
        val fromSequenceNr = withIterator { iter ⇒
          val startKey = Key(nid, 1L, 0)
          iter.seek(keyToBytes(startKey))
          if (iter.hasNext) keyFromBytes(iter.peekNext().getKey).sequenceNr else Long.MaxValue
        }

        fromSequenceNr to toSequenceNr foreach { sequenceNr ⇒
          batch.delete(keyToBytes(Key(nid, sequenceNr, 0)))
        }
      }
    } catch {
      case NonFatal(e) ⇒ Future.failed(e)
    }

  def leveldbSnapshot(): ReadOptions = leveldbReadOptions.snapshot(leveldb.getSnapshot)

  def withIterator[R](body: DBIterator ⇒ R): R = {
    val ro = leveldbSnapshot()
    val iterator = leveldb.iterator(ro)
    try {
      body(iterator)
    } finally {
      iterator.close()
      ro.snapshot().close()
    }
  }

  def withBatch[R](body: WriteBatch ⇒ R): R = {
    val batch = leveldb.createWriteBatch()
    try {
      val r = body(batch)
      leveldb.write(batch, leveldbWriteOptions)
      r
    } finally {
      batch.close()
    }
  }

  def persistentToBytes(p: PersistentRepr): Array[Byte] = serialization.serialize(p).get
  def persistentFromBytes(a: Array[Byte]): PersistentRepr = serialization.deserialize(a, classOf[PersistentRepr]).get

  private def addToMessageBatch(persistent: PersistentRepr, batch: WriteBatch): Unit = {
    val nid = numericId(persistent.persistenceId)
    batch.put(keyToBytes(counterKey(nid)), counterToBytes(persistent.sequenceNr))
    batch.put(keyToBytes(Key(nid, persistent.sequenceNr, 0)), persistentToBytes(persistent))
  }

  override def preStart() {
    leveldb = leveldbFactory.open(leveldbDir, if (nativeLeveldb) leveldbOptions else leveldbOptions.compressionType(CompressionType.NONE))
    super.preStart()
  }

  override def postStop() {
    leveldb.close()
    super.postStop()
  }

  protected def hasPersistenceIdSubscribers: Boolean = persistenceIdSubscribers.nonEmpty

  protected def addPersistenceIdSubscriber(subscriber: ActorRef, persistenceId: String): Unit =
    persistenceIdSubscribers.addBinding(persistenceId, subscriber)

  protected def removeSubscriber(subscriber: ActorRef): Unit = {
    val keys = persistenceIdSubscribers.collect { case (k, s) if s.contains(subscriber) ⇒ k }
    keys.foreach { key ⇒ persistenceIdSubscribers.removeBinding(key, subscriber) }

    allPersistenceIdsSubscribers -= subscriber
  }

  protected def hasAllPersistenceIdsSubscribers: Boolean = allPersistenceIdsSubscribers.nonEmpty

  protected def addAllPersistenceIdsSubscriber(subscriber: ActorRef): Unit = {
    allPersistenceIdsSubscribers += subscriber
    subscriber ! LeveldbJournal.CurrentPersistenceIds(allPersistenceIds)
  }

  private def notifyPersistenceIdChange(persistenceId: String): Unit =
    if (persistenceIdSubscribers.contains(persistenceId)) {
      val changed = LeveldbJournal.EventAppended(persistenceId)
      persistenceIdSubscribers(persistenceId).foreach(_ ! changed)
    }

  override protected def newPersistenceIdAdded(id: String): Unit = {
    if (hasAllPersistenceIdsSubscribers) {
      val added = LeveldbJournal.PersistenceIdAdded(id)
      allPersistenceIdsSubscribers.foreach(_ ! added)
    }
  }

}

