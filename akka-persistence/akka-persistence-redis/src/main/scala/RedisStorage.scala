/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.state

import org.codehaus.aspectwerkz.proxy.Uuid

object RedisStorage extends Storage {
  type ElementType = Array[Byte]

  def newMap: PersistentMap[ElementType, ElementType] = newMap(Uuid.newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(Uuid.newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(Uuid.newUuid.toString)
  override def newQueue: PersistentQueue[ElementType] = newQueue(Uuid.newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)
  override def getQueue(id: String): PersistentQueue[ElementType] = newQueue(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new RedisPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new RedisPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new RedisPersistentRef(id)
  override def newQueue(id: String): PersistentQueue[ElementType] = new RedisPersistentQueue(id)
}

/**
 * Implements a persistent transactional map based on the Redis storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class RedisPersistentMap(id: String) extends PersistentMap[Array[Byte], Array[Byte]] {
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
