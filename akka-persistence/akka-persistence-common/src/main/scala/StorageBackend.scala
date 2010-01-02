/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.state

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
}

// for Ref
trait RefStorageBackend[T] extends StorageBackend {
  def insertRefStorageFor(name: String, element: T)
  def getRefStorageFor(name: String): Option[T]
}
