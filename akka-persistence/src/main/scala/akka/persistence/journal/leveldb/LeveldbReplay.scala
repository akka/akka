/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import scala.concurrent.Future

import akka.persistence._
import akka.persistence.journal.AsyncReplay

/**
 * INTERNAL API.
 *
 * LevelDB backed message replay.
 */
private[persistence] trait LeveldbReplay extends AsyncReplay { this: LeveldbStore ⇒
  import Key._

  private lazy val replayDispatcherId = config.getString("replay-dispatcher")
  private lazy val replayDispatcher = context.system.dispatchers.lookup(replayDispatcherId)

  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: PersistentRepr ⇒ Unit): Future[Long] =
    Future(replay(numericId(processorId), fromSequenceNr: Long, toSequenceNr)(replayCallback))(replayDispatcher)

  def replay(processorId: Int, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: PersistentRepr ⇒ Unit): Long = {
    val iter = leveldbIterator

    @scala.annotation.tailrec
    def go(key: Key, replayCallback: PersistentRepr ⇒ Unit) {
      if (iter.hasNext) {
        val nextEntry = iter.next()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (nextKey.sequenceNr > toSequenceNr) {
          // end iteration here
        } else if (nextKey.channelId != 0) {
          // phantom confirmation (just advance iterator)
          go(nextKey, replayCallback)
        } else if (key.processorId == nextKey.processorId) {
          val msg = persistentFromBytes(nextEntry.getValue)
          val del = deletion(nextKey)
          val cnf = confirms(nextKey, Nil)
          replayCallback(msg.update(confirms = cnf, deleted = del))
          go(nextKey, replayCallback)
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
      go(startKey, replayCallback)
      maxSequenceNr(processorId)
    } finally {
      iter.close()
    }
  }

  def maxSequenceNr(processorId: Int) = {
    leveldb.get(keyToBytes(counterKey(processorId)), leveldbSnapshot) match {
      case null  ⇒ 0L
      case bytes ⇒ counterFromBytes(bytes)
    }
  }
}
