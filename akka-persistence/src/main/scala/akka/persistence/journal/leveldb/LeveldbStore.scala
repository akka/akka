/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import java.io.File

import scala.collection.immutable
import scala.util._

import org.iq80.leveldb._

import akka.actor._
import akka.persistence._
import akka.persistence.journal.AsyncWriteTarget
import akka.serialization.SerializationExtension

/**
 * INTERNAL API.
 */
private[persistence] trait LeveldbStore extends Actor with LeveldbIdMapping with LeveldbRecovery {
  val configPath: String

  val config = context.system.settings.config.getConfig(configPath)
  val nativeLeveldb = config.getBoolean("native")

  val leveldbOptions = new Options().createIfMissing(true)
  def leveldbReadOptions = new ReadOptions().verifyChecksums(config.getBoolean("checksum"))
  val leveldbWriteOptions = new WriteOptions().sync(config.getBoolean("fsync")).snapshot(false)
  val leveldbDir = new File(config.getString("dir"))
  var leveldb: DB = _

  def leveldbFactory =
    if (nativeLeveldb) org.fusesource.leveldbjni.JniDBFactory.factory
    else org.iq80.leveldb.impl.Iq80DBFactory.factory

  val serialization = SerializationExtension(context.system)

  import Key._

  def writeMessages(messages: immutable.Seq[PersistentRepr]) =
    withBatch(batch ⇒ messages.foreach(message ⇒ addToMessageBatch(message, batch)))

  def writeConfirmations(confirmations: immutable.Seq[PersistentConfirmation]) =
    withBatch(batch ⇒ confirmations.foreach(confirmation ⇒ addToConfirmationBatch(confirmation, batch)))

  def deleteMessages(messageIds: immutable.Seq[PersistentId], permanent: Boolean) = withBatch { batch ⇒
    messageIds foreach { messageId ⇒
      if (permanent) batch.delete(keyToBytes(Key(numericId(messageId.persistenceId), messageId.sequenceNr, 0)))
      else batch.put(keyToBytes(deletionKey(numericId(messageId.persistenceId), messageId.sequenceNr)), Array.emptyByteArray)
    }
  }

  def deleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean) = withBatch { batch ⇒
    val nid = numericId(persistenceId)

    // seek to first existing message
    val fromSequenceNr = withIterator { iter ⇒
      val startKey = Key(nid, 1L, 0)
      iter.seek(keyToBytes(startKey))
      if (iter.hasNext) keyFromBytes(iter.peekNext().getKey).sequenceNr else Long.MaxValue
    }

    fromSequenceNr to toSequenceNr foreach { sequenceNr ⇒
      if (permanent) batch.delete(keyToBytes(Key(nid, sequenceNr, 0))) // TODO: delete confirmations and deletion markers, if any.
      else batch.put(keyToBytes(deletionKey(nid, sequenceNr)), Array.emptyByteArray)
    }
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

  private def addToConfirmationBatch(confirmation: PersistentConfirmation, batch: WriteBatch): Unit = {
    val npid = numericId(confirmation.persistenceId)
    val ncid = numericId(confirmation.channelId)
    batch.put(keyToBytes(Key(npid, confirmation.sequenceNr, ncid)), confirmation.channelId.getBytes("UTF-8"))
  }

  override def preStart() {
    leveldb = leveldbFactory.open(leveldbDir, if (nativeLeveldb) leveldbOptions else leveldbOptions.compressionType(CompressionType.NONE))
    super.preStart()
  }

  override def postStop() {
    leveldb.close()
    super.postStop()
  }
}

/**
 * A LevelDB store that can be shared by multiple actor systems. The shared store must be
 * set for each actor system that uses the store via `SharedLeveldbJournal.setStore`. The
 * shared LevelDB store is for testing only.
 */
class SharedLeveldbStore extends { val configPath = "akka.persistence.journal.leveldb-shared.store" } with LeveldbStore {
  import AsyncWriteTarget._

  def receive = {
    case WriteMessages(msgs)                        ⇒ sender ! writeMessages(msgs)
    case WriteConfirmations(cnfs)                   ⇒ sender ! writeConfirmations(cnfs)
    case DeleteMessages(messageIds, permanent)      ⇒ sender ! deleteMessages(messageIds, permanent)
    case DeleteMessagesTo(pid, tsnr, permanent)     ⇒ sender ! deleteMessagesTo(pid, tsnr, permanent)
    case ReadHighestSequenceNr(pid, fromSequenceNr) ⇒ sender ! readHighestSequenceNr(numericId(pid))
    case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
      Try(replayMessages(numericId(pid), fromSnr, toSnr, max)(sender ! _)) match {
        case Success(max)   ⇒ sender ! ReplaySuccess
        case Failure(cause) ⇒ sender ! ReplayFailure(cause)
      }
  }
}
