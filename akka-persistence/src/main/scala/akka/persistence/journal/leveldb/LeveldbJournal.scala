/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import java.io.File

import scala.collection.immutable

import org.iq80.leveldb._

import akka.persistence._
import akka.persistence.journal.SyncWriteJournal
import akka.serialization.SerializationExtension

/**
 * INTERNAL API.
 *
 * LevelDB backed journal.
 */
private[leveldb] class LeveldbJournal extends SyncWriteJournal with LeveldbIdMapping with LeveldbReplay {
  val config = context.system.settings.config.getConfig("akka.persistence.journal.leveldb")

  val leveldbOptions = new Options().createIfMissing(true).compressionType(CompressionType.NONE)
  val leveldbReadOptions = new ReadOptions().verifyChecksums(config.getBoolean("checksum"))
  val leveldbWriteOptions = new WriteOptions().sync(config.getBoolean("fsync"))
  val leveldbDir = new File(config.getString("dir"))

  val leveldbFactory = org.iq80.leveldb.impl.Iq80DBFactory.factory
  var leveldb: DB = _

  // TODO: support migration of processor and channel ids
  // needed if default processor and channel ids are used
  // (actor paths, which contain deployment information).

  val serialization = SerializationExtension(context.system)

  import Key._

  def write(persistent: PersistentRepr) =
    withBatch(batch ⇒ addToBatch(persistent, batch))

  def writeBatch(persistentBatch: immutable.Seq[PersistentRepr]) =
    withBatch(batch ⇒ persistentBatch.foreach(persistent ⇒ addToBatch(persistent, batch)))

  def delete(processorId: String, sequenceNr: Long, physical: Boolean) {
    if (physical)
      // TODO: delete confirmations and deletion markers, if any.
      leveldb.delete(keyToBytes(Key(numericId(processorId), sequenceNr, 0)))
    else
      leveldb.put(keyToBytes(deletionKey(numericId(processorId), sequenceNr)), Array.empty[Byte])
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
    leveldb = leveldbFactory.open(leveldbDir, leveldbOptions)
    super.preStart()
  }

  override def postStop() {
    leveldb.close()
    super.postStop()
  }
}
