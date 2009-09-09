/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

// abstracts persistence storage
trait Storage

// for Maps
trait MapStorage extends Storage {
  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[AnyRef, AnyRef]])
  def insertMapStorageEntryFor(name: String, key: AnyRef, value: AnyRef)
  def removeMapStorageFor(name: String)
  def removeMapStorageFor(name: String, key: AnyRef)
  def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef]
  def getMapStorageSizeFor(name: String): Int
  def getMapStorageFor(name: String): List[Tuple2[AnyRef, AnyRef]]
  def getMapStorageRangeFor(name: String, start: Option[AnyRef], finish: Option[AnyRef], count: Int): List[Tuple2[AnyRef, AnyRef]]
}

// for Vectors
trait VectorStorage extends Storage {
  def insertVectorStorageEntryFor(name: String, element: AnyRef) 
  def insertVectorStorageEntriesFor(name: String, elements: List[AnyRef]) 
  def updateVectorStorageEntryFor(name: String, index: Int, elem: AnyRef)
  def getVectorStorageEntryFor(name: String, index: Int): AnyRef 
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): RandomAccessSeq[AnyRef]
  def getVectorStorageSizeFor(name: String): Int 
}

// for Ref
trait RefStorage extends Storage {
  def insertRefStorageFor(name: String, element: AnyRef)
  def getRefStorageFor(name: String): Option[AnyRef]
}
