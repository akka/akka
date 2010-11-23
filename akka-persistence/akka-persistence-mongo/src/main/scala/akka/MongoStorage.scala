/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.mongo

import akka.stm._
import akka.persistence.common._
import akka.actor.{newUuid}

object MongoStorage extends BytesStorage {
 val backend = MongoBackend
}


object MongoBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(MongoStorageBackend)
  val vectorStorage = Some(MongoStorageBackend)
  val queueStorage = None
  val mapStorage = Some(MongoStorageBackend)
}
