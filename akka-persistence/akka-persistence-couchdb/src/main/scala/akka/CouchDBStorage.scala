package akka.persistence.couchdb

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._

object CouchDBStorage extends Storage {
  type ElementType = Array[Byte]

  def newMap: PersistentMap[ElementType, ElementType] = newMap(newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new CouchDBPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new CouchDBPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new CouchDBPersistentRef(id)
}

/**
 * Implements a persistent transactional map based on the CouchDB storage.
 *
 * @author
 */
class CouchDBPersistentMap(id: String) extends PersistentMapBinary {
  val uuid = id
  val storage = CouchDBStorageBackend
}

/**
 * Implements a persistent transactional vector based on the CouchDB
 * storage.
 *
 * @author
 */
class CouchDBPersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = CouchDBStorageBackend
}

class CouchDBPersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = CouchDBStorageBackend
}
