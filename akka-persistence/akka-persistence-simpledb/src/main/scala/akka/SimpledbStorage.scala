/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.simpledb

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._


object SimpledbStorage extends Storage {

  type ElementType = Array[Byte]
  def newMap: PersistentMap[ElementType, ElementType] = newMap(newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(newUuid.toString)
  override def newQueue: PersistentQueue[ElementType] = newQueue(newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)
  override def getQueue(id: String): PersistentQueue[ElementType] = newQueue(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new SimpledbPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new SimpledbPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new SimpledbPersistentRef(id)
  override def newQueue(id:String): PersistentQueue[ElementType] = new SimpledbPersistentQueue(id)
}


class SimpledbPersistentMap(id: String) extends PersistentMapBinary {
  val uuid = id
  val storage = SimpledbStorageBackend
}


class SimpledbPersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = SimpledbStorageBackend
}

class SimpledbPersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = SimpledbStorageBackend
}

class SimpledbPersistentQueue(id: String) extends PersistentQueue[Array[Byte]] {
  val uuid = id
  val storage = SimpledbStorageBackend
}
