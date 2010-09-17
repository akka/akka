/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.redis

import se.scalablesolutions.akka.util.UUID
import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._

object RedisStorage extends Storage {
  type ElementType = Array[Byte]

  def newMap: PersistentMap[ElementType, ElementType] = newMap(UUID.newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(UUID.newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(UUID.newUuid.toString)
  override def newQueue: PersistentQueue[ElementType] = newQueue(UUID.newUuid.toString)
  override def newSortedSet: PersistentSortedSet[ElementType] = newSortedSet(UUID.newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)
  override def getQueue(id: String): PersistentQueue[ElementType] = newQueue(id)
  override def getSortedSet(id: String): PersistentSortedSet[ElementType] = newSortedSet(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new RedisPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new RedisPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new RedisPersistentRef(id)
  override def newQueue(id: String): PersistentQueue[ElementType] = new RedisPersistentQueue(id)
  override def newSortedSet(id: String): PersistentSortedSet[ElementType] =
    new RedisPersistentSortedSet(id)
}

/**
 * Implements a persistent transactional map based on the Redis storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class RedisPersistentMap(id: String) extends PersistentMapBinary {
  val uuid = id
  val storage = RedisStorageBackend
}

/**
 * Implements a persistent transactional vector based on the Redis
 * storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class RedisPersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = RedisStorageBackend
}

class RedisPersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = RedisStorageBackend
}

/**
 * Implements a persistent transactional queue based on the Redis
 * storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class RedisPersistentQueue(id: String) extends PersistentQueue[Array[Byte]] {
  val uuid = id
  val storage = RedisStorageBackend
}

/**
 * Implements a persistent transactional sorted set based on the Redis
 * storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class RedisPersistentSortedSet(id: String) extends PersistentSortedSet[Array[Byte]] {
  val uuid = id
  val storage = RedisStorageBackend
}
