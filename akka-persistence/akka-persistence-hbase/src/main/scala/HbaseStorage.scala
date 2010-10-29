/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.hbase

import akka.actor.{Uuid,newUuid}
import akka.stm._
import akka.persistence.common._

object HbaseStorage extends Storage {
  type ElementType = Array[Byte]

  def newMap: PersistentMap[ElementType, ElementType] = newMap(newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new HbasePersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new HbasePersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new HbasePersistentRef(id)
}

/**
 * Implements a persistent transactional map based on Hbase.
 *
 * @author <a href="http://www.davidgreco.it">David Greco</a>
 */
class HbasePersistentMap(id: String) extends PersistentMapBinary {
  val uuid = id
  val storage = HbaseStorageBackend
}

/**
 * Implements a persistent transactional vector based on Hbase.
 *
 * @author <a href="http://www.davidgreco.it">David Greco</a>
 */
class HbasePersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = HbaseStorageBackend
}

class HbasePersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = HbaseStorageBackend
}
