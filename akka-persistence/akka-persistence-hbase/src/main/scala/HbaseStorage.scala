/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.hbase

import akka.actor.{Uuid,newUuid}
import akka.stm._
import akka.persistence.common._

object HbaseStorage extends BytesStorage {
  val backend = HbaseBackend
}

object HbaseBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(HbaseStorageBackend)
  val vectorStorage = Some(HbaseStorageBackend)
  val queueStorage = None
  val mapStorage = Some(HbaseStorageBackend)
}
