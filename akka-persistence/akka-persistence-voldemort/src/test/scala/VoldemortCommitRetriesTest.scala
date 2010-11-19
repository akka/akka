package akka.persistence.voldemort


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common._


object VoldemortTestStorage extends BytesStorage {
  val backend = VoldemortTestBackend
}

object VoldemortTestBackend extends Backend[Array[Byte]] {
  val sortedSetStorage = None
  val refStorage = None
  val vectorStorage = Some(VoldemortTestStorageBackend)
  val queueStorage = None
  val mapStorage = Some(VoldemortTestStorageBackend)
}

object VoldemortTestStorageBackend extends MapStorageBackend[Array[Byte], Array[Byte]] with VectorStorageBackend[Array[Byte]] {

  var mapFail = true
  var vectorFail = true

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int) = VoldemortStorageBackend.getMapStorageRangeFor(name, start, finish, count)

  def getMapStorageFor(name: String) = VoldemortStorageBackend.getMapStorageFor(name)

  def getMapStorageSizeFor(name: String) = VoldemortStorageBackend.getMapStorageSizeFor(name)

  def getMapStorageEntryFor(name: String, key: Array[Byte]) = VoldemortStorageBackend.getMapStorageEntryFor(name, key)

  def removeMapStorageFor(name: String, key: Array[Byte]) = VoldemortStorageBackend.removeMapStorageFor(name, key)

  def removeMapStorageFor(name: String) = VoldemortStorageBackend.removeMapStorageFor(name)


  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    if (mapFail) {
      mapFail = false
      throw new StorageException("Forced Failure to Test Retries")
    }
    VoldemortStorageBackend.insertMapStorageEntryFor(name, key, value)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    if (mapFail) {
      mapFail = false
      throw new StorageException("Forced Failure to Test Retries")
    }
    VoldemortStorageBackend.insertMapStorageEntriesFor(name, entries)
  }

  def getVectorStorageSizeFor(name: String) = VoldemortStorageBackend.getVectorStorageSizeFor(name)

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int) = VoldemortStorageBackend.getVectorStorageRangeFor(name, start, finish, count)

  def getVectorStorageEntryFor(name: String, index: Int) = VoldemortStorageBackend.getVectorStorageEntryFor(name, index)

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = VoldemortStorageBackend.updateVectorStorageEntryFor(name, index, elem)

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    if (vectorFail) {
      vectorFail = false
      throw new StorageException("Forced Failure to Test Retries")
    }
    VoldemortStorageBackend.insertVectorStorageEntriesFor(name, elements)
  }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    if (vectorFail) {
      vectorFail = false
      throw new StorageException("Forced Failure to test retries")
    }
    VoldemortStorageBackend.insertVectorStorageEntryFor(name, element)

  }

  override def removeVectorStorageEntryFor(name: String) = VoldemortStorageBackend.removeVectorStorageEntryFor(name)
}

class VoldemortCommitRetriesTest extends Ticket343Test with EmbeddedVoldemort {
  def dropMapsAndVectors: Unit = {
    VoldemortStorageBackend.mapAccess.drop
    VoldemortStorageBackend.vectorAccess.drop
  }

  def getVector: (String) => PersistentVector[Array[Byte]] = VoldemortTestStorage.getVector

  def getMap: (String) => PersistentMap[Array[Byte], Array[Byte]] = VoldemortTestStorage.getMap

}