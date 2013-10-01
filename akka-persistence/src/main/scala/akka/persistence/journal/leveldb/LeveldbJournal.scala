/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import java.io.File

import scala.util._

import org.iq80.leveldb._

import com.typesafe.config.Config

import akka.actor._
import akka.pattern.PromiseActorRef
import akka.persistence._
import akka.serialization.{ Serialization, SerializationExtension }

/**
 * LevelDB journal settings.
 */
private[persistence] class LeveldbJournalSettings(config: Config) extends JournalFactory {
  /**
   * Name of directory where journal files shall be stored. Can be a relative or absolute path.
   */
  val journalDir: File = new File(config.getString("dir"))

  /**
   * Verify checksums on read.
   */
  val checksum = false

  /**
   * Synchronous writes to disk.
   */
  val fsync: Boolean = config.getBoolean("fsync")

  /**
   * Creates a new LevelDB journal actor from this configuration object.
   */
  def createJournal(implicit factory: ActorRefFactory): ActorRef =
    factory.actorOf(Props(classOf[LeveldbJournal], this).withDispatcher("akka.persistence.journal.leveldb.write.dispatcher"))
}

/**
 * LevelDB journal.
 */
private[persistence] class LeveldbJournal(val settings: LeveldbJournalSettings) extends Actor with LeveldbIdMapping with LeveldbReplay {
  val extension = Persistence(context.system)

  val leveldbOptions = new Options().createIfMissing(true).compressionType(CompressionType.NONE)
  val leveldbReadOptions = new ReadOptions().verifyChecksums(settings.checksum)
  val leveldbWriteOptions = new WriteOptions().sync(settings.fsync)

  val leveldbDir = settings.journalDir
  val leveldbFactory = org.iq80.leveldb.impl.Iq80DBFactory.factory
  var leveldb: DB = _

  // TODO: support migration of processor and channel ids
  // needed if default processor and channel ids are used
  // (actor paths, which contain deployment information).

  // TODO: use protobuf serializer for PersistentImpl
  // TODO: use user-defined serializer for payload
  val serializer = SerializationExtension(context.system).findSerializerFor("")

  import Journal._
  import Key._

  import context.dispatcher

  def receive = {
    case Write(persistent, processor) ⇒ {
      val persisted = withBatch { batch ⇒
        val sdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
        val nid = numericId(persistent.processorId)
        val prepared = persistent.copy(sender = Serialization.serializedActorPath(sdr))
        batch.put(keyToBytes(counterKey(nid)), counterToBytes(prepared.sequenceNr))
        batch.put(keyToBytes(Key(nid, prepared.sequenceNr, 0)), persistentToBytes(prepared.copy(resolved = false, confirmTarget = null, confirmMessage = null)))
        prepared
      }
      processor.tell(Written(persisted), sender)
    }
    case c @ Confirm(processorId, sequenceNr, channelId) ⇒ {
      leveldb.put(keyToBytes(Key(numericId(processorId), sequenceNr, numericId(channelId))), channelId.getBytes("UTF-8"))
      context.system.eventStream.publish(c) // TODO: turn off by default and allow to turn on by configuration
    }
    case Delete(persistent: PersistentImpl) ⇒ {
      leveldb.put(keyToBytes(deletionKey(numericId(persistent.processorId), persistent.sequenceNr)), Array.empty[Byte])
    }
    case Loop(message, processor) ⇒ {
      processor.tell(Looped(message), sender)
    }
    case Replay(fromSequenceNr, toSequenceNr, processor, processorId) ⇒ {
      val maxSnr = maxSequenceNr(processorId)
      replayAsync(fromSequenceNr, toSequenceNr, processor, processorId) onComplete {
        case Success(_) ⇒ processor ! ReplayCompleted(maxSnr)
        case Failure(e) ⇒ // TODO: send RecoveryFailed to processor
      }
    }
  }

  def leveldbSnapshot = leveldbReadOptions.snapshot(leveldb.getSnapshot)
  def leveldbIterator = leveldb.iterator(leveldbSnapshot)

  def persistentToBytes(p: PersistentImpl): Array[Byte] = serializer.toBinary(p)
  def persistentFromBytes(a: Array[Byte]): PersistentImpl = serializer.fromBinary(a).asInstanceOf[PersistentImpl]

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

  def maxSequenceNr(processorId: String) = {
    leveldb.get(keyToBytes(counterKey(numericId(processorId))), leveldbSnapshot) match {
      case null  ⇒ 0L
      case bytes ⇒ counterFromBytes(bytes)
    }
  }

  override def preStart() {
    leveldb = leveldbFactory.open(leveldbDir, leveldbOptions)
    super.preStart()
  }

  override def postStop() {
    super.postStop()
    leveldb.close()
  }
}

