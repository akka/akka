/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.common

// abstracts persistence storage
trait StorageBackend

// for Maps
trait MapStorageBackend[K, V] extends StorageBackend {
  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[K, V]])
  def insertMapStorageEntryFor(name: String, key: K, value: V)
  def removeMapStorageFor(name: String)
  def removeMapStorageFor(name: String, key: K)
  def getMapStorageEntryFor(name: String, key: K): Option[V]
  def getMapStorageSizeFor(name: String): Int
  def getMapStorageFor(name: String): List[Tuple2[K, V]]
  def getMapStorageRangeFor(name: String, start: Option[K], finish: Option[K], count: Int): List[Tuple2[K, V]]
}

// for Vectors
trait VectorStorageBackend[T] extends StorageBackend {
  def insertVectorStorageEntryFor(name: String, element: T)
  def insertVectorStorageEntriesFor(name: String, elements: List[T])
  def updateVectorStorageEntryFor(name: String, index: Int, elem: T)
  def getVectorStorageEntryFor(name: String, index: Int): T
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[T]
  def getVectorStorageSizeFor(name: String): Int
  def removeVectorStorageEntryFor(name:String):Unit = {
    //should remove the "tail" if supported
    throw new UnsupportedOperationException("VectorStorageBackend.removeVectorStorageEntry is not supported")
  }
}

// for Ref
trait RefStorageBackend[T] extends StorageBackend {
  def insertRefStorageFor(name: String, element: T)
  def getRefStorageFor(name: String): Option[T]
}

// for Queue
trait QueueStorageBackend[T] extends StorageBackend {
  // add to the end of the queue
  def enqueue(name: String, item: T): Option[Int]

  // pop from the front of the queue
  def dequeue(name: String): Option[T]

  // get the size of the queue
  def size(name: String): Int

  // return an array of items currently stored in the queue
  // start is the item to begin, count is how many items to return
  def peek(name: String, start: Int, count: Int): List[T]

  // completely delete the queue
  def remove(name: String): Boolean
}

trait SortedSetStorageBackend[T] extends StorageBackend {
  // add item to sorted set identified by name
  def zadd(name: String, zscore: String, item: T): Boolean

  // remove item from sorted set identified by name
  def zrem(name: String, item: T): Boolean

  // cardinality of the set identified by name
  def zcard(name: String): Int

  // zscore of the item from sorted set identified by name
  def zscore(name: String, item: T): Option[Float]

  // zrange from the sorted set identified by name
  def zrange(name: String, start: Int, end: Int): List[T]

  // zrange with score from the sorted set identified by name
  def zrangeWithScore(name: String, start: Int, end: Int): List[(T, Float)]
}
