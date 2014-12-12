/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.leveldb

import org.iq80.leveldb.DBIterator

import akka.actor.Actor

/**
 * INTERNAL API.
 *
 * LevelDB backed persistent mapping of `String`-based persistent actor ids to numeric ids.
 */
private[persistence] trait LeveldbIdMapping extends Actor { this: LeveldbStore ⇒
  import Key._

  private val idOffset = 10
  private var idMap: Map[String, Int] = Map.empty

  /**
   * Get the mapped numeric id for the specified persistent actor `id`. Creates and
   * stores a new mapping if necessary.
   */
  def numericId(id: String): Int = idMap.get(id) match {
    case None    ⇒ writeIdMapping(id, idMap.size + idOffset)
    case Some(v) ⇒ v
  }

  private def readIdMap(): Map[String, Int] = withIterator { iter ⇒
    iter.seek(keyToBytes(mappingKey(idOffset)))
    readIdMap(Map.empty, iter)
  }

  private def readIdMap(pathMap: Map[String, Int], iter: DBIterator): Map[String, Int] = {
    if (!iter.hasNext) pathMap else {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (!isMappingKey(nextKey)) pathMap else {
        val nextVal = new String(nextEntry.getValue, "UTF-8")
        readIdMap(pathMap + (nextVal -> nextKey.mappingId), iter)
      }
    }
  }

  private def writeIdMapping(id: String, numericId: Int): Int = {
    idMap = idMap + (id -> numericId)
    leveldb.put(keyToBytes(mappingKey(numericId)), id.getBytes("UTF-8"))
    numericId
  }

  override def preStart() {
    idMap = readIdMap()
    super.preStart()
  }
}
