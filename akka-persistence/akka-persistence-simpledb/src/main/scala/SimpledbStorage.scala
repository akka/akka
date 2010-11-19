/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.simpledb

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._


object SimpledbStorage extends BytesStorage {
  val backend = SimpledbBackend
}

object SimpledbBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(SimpledbStorageBackend)
  val vectorStorage = Some(SimpledbStorageBackend)
  val queueStorage = Some(SimpledbStorageBackend)
  val mapStorage = Some(SimpledbStorageBackend)
}
