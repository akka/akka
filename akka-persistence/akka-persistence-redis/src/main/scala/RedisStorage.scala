/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.redis

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._

object RedisStorage extends BytesStorage {
  val backend = RedisBackend
}

object RedisBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = Some(RedisStorageBackend)
  val refStorage = Some(RedisStorageBackend)
  val vectorStorage = Some(RedisStorageBackend)
  val queueStorage = Some(RedisStorageBackend)
  val mapStorage = Some(RedisStorageBackend)
}

