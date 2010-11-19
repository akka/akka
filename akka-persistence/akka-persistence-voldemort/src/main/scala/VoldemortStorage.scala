/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.voldemort

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._


object VoldemortStorage extends BytesStorage {
  val backend = VoldemortBackend
}

object VoldemortBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(VoldemortStorageBackend)
  val vectorStorage = Some(VoldemortStorageBackend)
  val queueStorage = Some(VoldemortStorageBackend)
  val mapStorage = Some(VoldemortStorageBackend)
  val storageManager = new DefaultStorageManager[Array[Byte]]
}