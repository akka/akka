/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.mongo

import akka.stm._
import akka.persistence.common._
import akka.actor.{newUuid}

object MongoStorage extends Storage {
  type ElementType = Array[Byte]

  def newMap: PersistentMap[ElementType, ElementType] = newMap(newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new MongoPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new MongoPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new MongoPersistentRef(id)
}

/**
 * Implements a persistent transactional map based on the MongoDB document storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class MongoPersistentMap(id: String) extends PersistentMapBinary {
  val uuid = id
  val storage = MongoStorageBackend
}

/**
 * Implements a persistent transactional vector based on the MongoDB
 * document  storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = MongoStorageBackend
}

class MongoPersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = MongoStorageBackend
}
