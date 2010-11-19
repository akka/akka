package akka.persistence.couchdb

import akka.actor.{newUuid}
import akka.stm._
import akka.persistence.common._

object CouchDBStorage extends BytesStorage {
 val backend = CouchDBBackend
}

object CouchDBBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = Some(CouchDBStorageBackend)
  val vectorStorage = Some(CouchDBStorageBackend)
  val queueStorage = None
  val mapStorage = Some(CouchDBStorageBackend)
}


