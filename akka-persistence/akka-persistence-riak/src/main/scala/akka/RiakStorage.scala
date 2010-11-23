/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.riak

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._


object RiakStorage extends BytesStorage {
val backend = RiakBackend
}

object RiakBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(RiakStorageBackend)
  val vectorStorage = Some(RiakStorageBackend)
  val queueStorage = Some(RiakStorageBackend)
  val mapStorage = Some(RiakStorageBackend)
}
