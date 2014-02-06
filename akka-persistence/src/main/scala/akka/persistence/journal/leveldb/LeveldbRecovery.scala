/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import scala.concurrent.Future

import akka.persistence._
import akka.persistence.journal.AsyncRecovery
import org.iq80.leveldb.DBIterator

/**
 * INTERNAL API.
 *
 * LevelDB backed message replay and sequence number recovery.
 */
private[persistence] trait LeveldbRecovery extends AsyncRecovery { this: LeveldbStore ⇒
  import Key._

  private lazy val replayDispatcherId = config.getString("replay-dispatcher")
  private lazy val replayDispatcher = context.system.dispatchers.lookup(replayDispatcherId)

  def asyncReadHighestSequenceNr(processorId: String, fromSequenceNr: Long): Future[Long] =
    Future(readHighestSequenceNr(numericId(processorId)))(replayDispatcher)

  def asyncReplayMessages(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: PersistentRepr ⇒ Unit): Future[Unit] =
    Future(replayMessages(numericId(processorId), fromSequenceNr: Long, toSequenceNr, max: Long)(replayCallback))(replayDispatcher)

  def replayMessages(processorId: Int, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: PersistentRepr ⇒ Unit): Unit = {
    @scala.annotation.tailrec
    def go(iter: DBIterator, key: Key, ctr: Long, replayCallback: PersistentRepr ⇒ Unit) {
      if (iter.hasNext) {
        val nextEntry = iter.next()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (nextKey.sequenceNr > toSequenceNr) {
          // end iteration here
        } else if (nextKey.channelId != 0) {
          // phantom confirmation (just advance iterator)
          go(iter, nextKey, ctr, replayCallback)
        } else if (key.processorId == nextKey.processorId) {
          val msg = persistentFromBytes(nextEntry.getValue)
          val del = deletion(iter, nextKey)
          val cnf = confirms(iter, nextKey, Nil)
          if (ctr < max) {
            replayCallback(msg.update(confirms = cnf, deleted = del))
            go(iter, nextKey, ctr + 1L, replayCallback)
          }
        }
      }
    }

    @scala.annotation.tailrec
    def confirms(iter: DBIterator, key: Key, channelIds: List[String]): List[String] = {
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

    def deletion(iter: DBIterator, key: Key): Boolean = {
      if (iter.hasNext) {
        val nextEntry = iter.peekNext()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (key.processorId == nextKey.processorId && key.sequenceNr == nextKey.sequenceNr && isDeletionKey(nextKey)) {
          iter.next()
          true
        } else false
      } else false
    }

    withIterator { iter ⇒
      val startKey = Key(processorId, if (fromSequenceNr < 1L) 1L else fromSequenceNr, 0)
      iter.seek(keyToBytes(startKey))
      go(iter, startKey, 0L, replayCallback)
    }
  }

  def readHighestSequenceNr(processorId: Int) = {
    leveldb.get(keyToBytes(counterKey(processorId)), leveldbSnapshot) match {
      case null  ⇒ 0L
      case bytes ⇒ counterFromBytes(bytes)
    }
  }
}
