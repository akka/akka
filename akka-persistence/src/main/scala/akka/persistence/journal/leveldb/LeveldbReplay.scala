/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import scala.concurrent.Future

import org.iq80.leveldb.DBIterator

import akka.actor._
import akka.persistence._
import akka.persistence.Journal._

/**
 * Asynchronous replay support.
 */
private[persistence] trait LeveldbReplay extends Actor { this: LeveldbJournal ⇒
  import Key._

  private val executionContext = context.system.dispatchers.lookup("akka.persistence.journal.leveldb.replay.dispatcher")

  def replayAsync(fromSequenceNr: Long, toSequenceNr: Long, processor: ActorRef, processorId: String): Future[Unit] =
    Future(replay(fromSequenceNr: Long, toSequenceNr, processor, numericId(processorId), leveldbIterator))(executionContext)

  private def replay(fromSequenceNr: Long, toSequenceNr: Long, processor: ActorRef, processorId: Int, iter: DBIterator): Unit = {
    @scala.annotation.tailrec
    def go(key: Key)(callback: PersistentImpl ⇒ Unit) {
      if (iter.hasNext) {
        val nextEntry = iter.next()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (nextKey.sequenceNr > toSequenceNr) {
          // end iteration here
        } else if (nextKey.channelId != 0) {
          // phantom confirmation (just advance iterator)
          go(nextKey)(callback)
        } else if (key.processorId == nextKey.processorId) {
          val msg = persistentFromBytes(nextEntry.getValue)
          val del = deletion(nextKey)
          val cnf = confirms(nextKey, Nil)
          if (!del) callback(msg.copy(confirms = cnf))
          go(nextKey)(callback)
        }
      }
    }

    @scala.annotation.tailrec
    def confirms(key: Key, channelIds: List[String]): List[String] = {
      if (iter.hasNext) {
        val nextEntry = iter.peekNext()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (key.processorId == nextKey.processorId && key.sequenceNr == nextKey.sequenceNr) {
          val nextValue = new String(nextEntry.getValue, "UTF-8")
          iter.next()
          confirms(nextKey, nextValue :: channelIds)
        } else channelIds
      } else channelIds
    }

    def deletion(key: Key): Boolean = {
      if (iter.hasNext) {
        val nextEntry = iter.peekNext()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (key.processorId == nextKey.processorId && key.sequenceNr == nextKey.sequenceNr && isDeletionKey(nextKey)) {
          iter.next()
          true
        } else false
      } else false
    }

    try {
      val startKey = Key(processorId, if (fromSequenceNr < 1L) 1L else fromSequenceNr, 0)
      iter.seek(keyToBytes(startKey))
      go(startKey) { m ⇒ processor.tell(Replayed(m), extension.system.provider.resolveActorRef(m.sender)) }
    } finally {
      iter.close()
    }
  }
}
