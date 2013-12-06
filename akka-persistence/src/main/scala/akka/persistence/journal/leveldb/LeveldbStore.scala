/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
private[persistence] trait LeveldbStore extends Actor with LeveldbIdMapping with LeveldbReplay {
  val configPath: String

  val config = context.system.settings.config.getConfig(configPath)
  val nativeLeveldb = config.getBoolean("native")

  val leveldbOptions = new Options().createIfMissing(true)
  val leveldbReadOptions = new ReadOptions().verifyChecksums(config.getBoolean("checksum"))
  val leveldbWriteOptions = new WriteOptions().sync(config.getBoolean("fsync"))
  val leveldbDir = new File(config.getString("dir"))
  var leveldb: DB = _

  def leveldbFactory =
    if (nativeLeveldb) org.fusesource.leveldbjni.JniDBFactory.factory
    else org.iq80.leveldb.impl.Iq80DBFactory.factory

  // TODO: support migration of processor and channel ids
  // needed if default processor and channel ids are used
  // (actor paths, which contain deployment information).

  val serialization = SerializationExtension(context.system)

  import Key._

  def write(persistentBatch: immutable.Seq[PersistentRepr]) =
    withBatch(batch ⇒ persistentBatch.foreach(persistent ⇒ addToBatch(persistent, batch)))

  def delete(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean) = withBatch { batch ⇒
    val nid = numericId(processorId)
    if (permanent) fromSequenceNr to toSequenceNr foreach { sequenceNr ⇒
      batch.delete(keyToBytes(Key(nid, sequenceNr, 0))) // TODO: delete confirmations and deletion markers, if any.
    }
    else fromSequenceNr to toSequenceNr foreach { sequenceNr ⇒
      batch.put(keyToBytes(deletionKey(nid, sequenceNr)), Array.empty[Byte])
    }
  }

  def confirm(processorId: String, sequenceNr: Long, channelId: String) {
    leveldb.put(keyToBytes(Key(numericId(processorId), sequenceNr, numericId(channelId))), channelId.getBytes("UTF-8"))
  }

  def leveldbSnapshot = leveldbReadOptions.snapshot(leveldb.getSnapshot)
  def leveldbIterator = leveldb.iterator(leveldbSnapshot)

  def persistentToBytes(p: PersistentRepr): Array[Byte] = serialization.serialize(p).get
  def persistentFromBytes(a: Array[Byte]): PersistentRepr = serialization.deserialize(a, classOf[PersistentRepr]).get

  private def addToBatch(persistent: PersistentRepr, batch: WriteBatch): Unit = {
    val nid = numericId(persistent.processorId)
    batch.put(keyToBytes(counterKey(nid)), counterToBytes(persistent.sequenceNr))
    batch.put(keyToBytes(Key(nid, persistent.sequenceNr, 0)), persistentToBytes(persistent))
  }

  private def withBatch[R](body: WriteBatch ⇒ R): R = {
    val batch = leveldb.createWriteBatch()
    try {
      val r = body(batch)
      leveldb.write(batch, leveldbWriteOptions)
      r
    } finally {
      batch.close()
    }
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
    case WriteBatch(pb)                     ⇒ sender ! write(pb)
    case Delete(pid, fsnr, tsnr, permanent) ⇒ sender ! delete(pid, fsnr, tsnr, permanent)
    case Confirm(pid, snr, cid)             ⇒ sender ! confirm(pid, snr, cid)
    case Replay(pid, fromSnr, toSnr) ⇒
      val npid = numericId(pid)
      val res = for {
        _ ← Try(replay(npid, fromSnr, toSnr)(sender ! _))
        max ← Try(maxSequenceNr(npid))
      } yield max
      res match {
        case Success(max)   ⇒ sender ! ReplaySuccess(max)
        case Failure(cause) ⇒ sender ! ReplayFailure(cause)
      }
  }
}
