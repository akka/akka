/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.memcached

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._


object MemcachedStorage extends BytesStorage {
 val backend = MemcachedBackend
}

object MemcachedBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(MemcachedStorageBackend)
  val vectorStorage = Some(MemcachedStorageBackend)
  val queueStorage = Some(MemcachedStorageBackend)
  val mapStorage = Some(MemcachedStorageBackend)
}
