/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import java.io.File
import java.nio.ByteBuffer

import com.typesafe.config.Config

import org.iq80.leveldb._

import akka.actor._
import akka.pattern.PromiseActorRef
import akka.persistence._
import akka.serialization.{ Serialization, SerializationExtension }

/**
 * LevelDB journal configuration object.
 */
private[persistence] class LeveldbJournalSettings(config: Config) extends JournalFactory {
  /**
   * Name of directory where journal files shall be stored. Can be a relative or absolute path.
   */
  val dir: String = config.getString("dir")

  /**
   * Currently `false`.
   */
  val checksum = false

  /**
   * Currently `false`.
   */
  val fsync = false

  /**
   * Creates a new LevelDB journal actor from this configuration object.
   */
  def createJournal(implicit factory: ActorRefFactory): ActorRef =
    factory.actorOf(Props(classOf[LeveldbJournal], this).withDispatcher("akka.persistence.journal.leveldb.dispatcher"))
}

/**
 * LevelDB journal.
 */
private[persistence] class LeveldbJournal(settings: LeveldbJournalSettings) extends Actor {
  // TODO: support migration of processor and channel ids
  // needed if default processor and channel ids are used
  // (actor paths, which contain deployment information).

  val leveldbOptions = new Options().createIfMissing(true).compressionType(CompressionType.NONE)
  val levelDbReadOptions = new ReadOptions().verifyChecksums(settings.checksum)
  val levelDbWriteOptions = new WriteOptions().sync(settings.fsync)

  val leveldbFactory = org.iq80.leveldb.impl.Iq80DBFactory.factory
  var leveldb: DB = _

  val numericIdOffset = 10
  var pathMap: Map[String, Int] = Map.empty

  // TODO: use protobuf serializer for PersistentImpl
  // TODO: use user-defined serializer for payload
  val serializer = SerializationExtension(context.system).findSerializerFor("")

  import LeveldbJournal._
  import Journal._

  def receive = {
    case Write(pm, p) ⇒ {
      val sm = withBatch { batch ⇒
        // must be done because PromiseActorRef instances have no uid set TODO: discuss
        val ps = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
        val sm = pm.copy(sender = Serialization.serializedActorPath(ps))
        val pid = numericId(sm.processorId)
        batch.put(keyToBytes(counterKey(pid)), counterToBytes(sm.sequenceNr))
        batch.put(keyToBytes(Key(pid, sm.sequenceNr, 0)), msgToBytes(sm.copy(resolved = false, confirmTarget = null, confirmMessage = null)))
        sm
      }
      p.tell(Written(sm), sender)
    }
    case c @ Confirm(pid, snr, cid) ⇒ {
      leveldb.put(keyToBytes(Key(numericId(pid), snr, numericId(cid))), cid.getBytes("UTF-8"))
      // TODO: turn off by default and allow to turn on by configuration
      context.system.eventStream.publish(c)
    }
    case Delete(pm: PersistentImpl) ⇒ {
      leveldb.put(keyToBytes(deletionKey(numericId(pm.processorId), pm.sequenceNr)), Array.empty[Byte])
    }
    case Loop(m, p) ⇒ {
      p.tell(Looped(m), sender)
    }
    case Replay(toSnr, p, pid) ⇒ {
      val options = levelDbReadOptions.snapshot(leveldb.getSnapshot)
      val iter = leveldb.iterator(options)
      val maxSnr = leveldb.get(keyToBytes(counterKey(numericId(pid))), options) match {
        case null  ⇒ 0L
        case bytes ⇒ counterFromBytes(bytes)
      }
      context.actorOf(Props(classOf[LeveldbReplay], msgFromBytes _)) ! LeveldbReplay.Replay(toSnr, maxSnr, p, numericId(pid), iter)
    }
  }

  private def msgToBytes(m: PersistentImpl): Array[Byte] = serializer.toBinary(m)
  private def msgFromBytes(a: Array[Byte]): PersistentImpl = serializer.fromBinary(a).asInstanceOf[PersistentImpl]

  // ----------------------------------------------------------
  //  Path mapping
  // ----------------------------------------------------------

  private def numericId(processorId: String): Int = pathMap.get(processorId) match {
    case None    ⇒ writePathMapping(processorId, pathMap.size + numericIdOffset)
    case Some(v) ⇒ v
  }

  private def readPathMap(): Map[String, Int] = {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      iter.seek(keyToBytes(idToKey(numericIdOffset)))
      readPathMap(Map.empty, iter)
    } finally {
      iter.close()
    }
  }

  private def readPathMap(pathMap: Map[String, Int], iter: DBIterator): Map[String, Int] = {
    if (!iter.hasNext) pathMap else {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (!isMappingKey(nextKey)) pathMap else {
        val nextVal = new String(nextEntry.getValue, "UTF-8")
        readPathMap(pathMap + (nextVal -> idFromKey(nextKey)), iter)
      }
    }
  }

  private def writePathMapping(path: String, numericId: Int): Int = {
    pathMap = pathMap + (path -> numericId)
    leveldb.put(keyToBytes(idToKey(numericId)), path.getBytes("UTF-8"))
    numericId
  }

  // ----------------------------------------------------------
  //  Batch write support
  // ----------------------------------------------------------

  def withBatch[R](body: WriteBatch ⇒ R): R = {
    val batch = leveldb.createWriteBatch()
    try {
      val r = body(batch)
      leveldb.write(batch, levelDbWriteOptions)
      r
    } finally {
      batch.close()
    }
  }

  // ----------------------------------------------------------
  //  Life cycle
  // ----------------------------------------------------------

  override def preStart() {
    leveldb = leveldbFactory.open(new File(settings.dir), leveldbOptions)
    pathMap = readPathMap()
  }

  override def postStop() {
    leveldb.close()
  }
}

private object LeveldbJournal {
  case class Key(
    processorId: Int,
    sequenceNr: Long,
    channelId: Int)

  def idToKey(id: Int) = Key(1, 0L, id)
  def idFromKey(key: Key) = key.channelId

  def counterKey(processorId: Int): Key = Key(processorId, 0L, 0)
  def isMappingKey(key: Key): Boolean = key.processorId == 1

  def deletionKey(processorId: Int, sequenceNr: Long): Key = Key(processorId, sequenceNr, 1)
  def isDeletionKey(key: Key): Boolean = key.channelId == 1

  def counterToBytes(ctr: Long): Array[Byte] = ByteBuffer.allocate(8).putLong(ctr).array
  def counterFromBytes(bytes: Array[Byte]): Long = ByteBuffer.wrap(bytes).getLong

  def keyToBytes(key: Key): Array[Byte] = {
    val bb = ByteBuffer.allocate(20)
    bb.putInt(key.processorId)
    bb.putLong(key.sequenceNr)
    bb.putInt(key.channelId)
    bb.array
  }

  def keyFromBytes(bytes: Array[Byte]): Key = {
    val bb = ByteBuffer.wrap(bytes)
    val aid = bb.getInt
    val snr = bb.getLong
    val cid = bb.getInt
    new Key(aid, snr, cid)
  }
}

private class LeveldbReplay(deserialize: Array[Byte] ⇒ PersistentImpl) extends Actor {
  val extension = Persistence(context.system)

  import LeveldbReplay._
  import LeveldbJournal._
  import Journal.{ Replayed, RecoveryEnd }

  // TODO: parent should stop replay actor if it crashes
  // TODO: use a pinned dispatcher

  def receive = {
    case Replay(toSnr, maxSnr, processor, processorId, iter) ⇒ {
      try {
        val startKey = Key(processorId, 1L, 0)
        iter.seek(keyToBytes(startKey))
        replay(iter, startKey, toSnr, m ⇒ processor.tell(Replayed(m), extension.system.provider.resolveActorRef(m.sender)))
      } finally { iter.close() }
      processor.tell(RecoveryEnd(maxSnr), self)
      context.stop(self)
    }
  }

  @scala.annotation.tailrec
  private def replay(iter: DBIterator, key: Key, toSnr: Long, callback: PersistentImpl ⇒ Unit) {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.sequenceNr > toSnr) {
        // end iteration here
      } else if (nextKey.channelId != 0) {
        // phantom confirmation (just advance iterator)
        replay(iter, nextKey, toSnr, callback)
      } else if (key.processorId == nextKey.processorId) {
        val msg = deserialize(nextEntry.getValue)
        val del = deletion(iter, nextKey)
        val cnf = confirms(iter, nextKey, Nil)
        if (!del) callback(msg.copy(confirms = cnf))
        replay(iter, nextKey, toSnr, callback)
      }
    }
  }

  private def deletion(iter: DBIterator, key: Key): Boolean = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (key.processorId == nextKey.processorId && key.sequenceNr == nextKey.sequenceNr && isDeletionKey(nextKey)) {
        iter.next()
        true
      } else false
    } else false
  }

  @scala.annotation.tailrec
  private def confirms(iter: DBIterator, key: Key, channelIds: List[String]): List[String] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (key.processorId == nextKey.processorId && key.sequenceNr == nextKey.sequenceNr) {
        val nextValue = new String(nextEntry.getValue, "UTF-8")
        iter.next()
        confirms(iter, nextKey, nextValue :: channelIds)
      } else channelIds
    } else channelIds
  }

}

private object LeveldbReplay {
  case class Replay(toSequenceNr: Long, maxSequenceNr: Long, processor: ActorRef, pid: Int, iterator: DBIterator)
}
