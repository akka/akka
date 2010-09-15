/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.voldemort

import se.scalablesolutions.akka.util.UUID
import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._


object VoldemortStorage extends Storage {

  type ElementType = Array[Byte]
  def newMap: PersistentMap[ElementType, ElementType] = newMap(UUID.newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(UUID.newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(UUID.newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new VoldemortPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new VoldemortPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new VoldemortPersistentRef(id)
}


class VoldemortPersistentMap(id: String) extends PersistentMapBinary {
  val uuid = id
  val storage = VoldemortStorageBackend
}


class VoldemortPersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = VoldemortStorageBackend
}

class VoldemortPersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = VoldemortStorageBackend
}
