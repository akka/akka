/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.cassandra

import akka.stm._
import akka.persistence.common._
import akka.actor.{newUuid}

object CassandraStorage extends BytesStorage {
  val backend = CassandraBackend
}

object CassandraBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(CassandraStorageBackend)
  val vectorStorage = Some(CassandraStorageBackend)
  val queueStorage = Some(CassandraStorageBackend)
  val mapStorage = Some(CassandraStorageBackend)
}


