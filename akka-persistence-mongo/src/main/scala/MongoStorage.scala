/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import org.codehaus.aspectwerkz.proxy.Uuid

object MongoStorage extends Storage {
  type ElementType = AnyRef

  def newMap: PersistentMap[ElementType, ElementType] = newMap(Uuid.newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(Uuid.newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(Uuid.newUuid.toString)

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
class MongoPersistentMap(id: String) extends PersistentMap[AnyRef, AnyRef] {
  val uuid = id
  val storage = MongoStorageBackend
}

/**
 * Implements a persistent transactional vector based on the MongoDB
 * document  storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentVector(id: String) extends PersistentVector[AnyRef] {
  val uuid = id
  val storage = MongoStorageBackend
}

class MongoPersistentRef(id: String) extends PersistentRef[AnyRef] {
  val uuid = id
  val storage = MongoStorageBackend
}