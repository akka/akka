/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import java.nio.ByteBuffer

/**
 * LevelDB key.
 */
private[leveldb] final case class Key(
  persistenceId: Int,
  sequenceNr: Long,
  channelId: Int)

private[leveldb] object Key {
  def keyToBytes(key: Key): Array[Byte] = {
    val bb = ByteBuffer.allocate(20)
    bb.putInt(key.persistenceId)
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

  def counterKey(persistenceId: Int): Key = Key(persistenceId, 0L, 0)
  def counterToBytes(ctr: Long): Array[Byte] = ByteBuffer.allocate(8).putLong(ctr).array
  def counterFromBytes(bytes: Array[Byte]): Long = ByteBuffer.wrap(bytes).getLong

  def id(key: Key) = key.channelId
  def idKey(id: Int) = Key(1, 0L, id)
  def isIdKey(key: Key): Boolean = key.persistenceId == 1

  def deletionKey(persistenceId: Int, sequenceNr: Long): Key = Key(persistenceId, sequenceNr, 1)
  def isDeletionKey(key: Key): Boolean = key.channelId == 1
}

