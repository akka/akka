package akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.voldemort.VoldemortStorageBackend._
import akka.persistence.common.CommonStorageBackend._
import akka.persistence.common.KVStorageBackend._
import akka.util.Logging
import collection.immutable.TreeSet
import VoldemortStorageBackendSuite._
import scala.None

@RunWith(classOf[JUnitRunner])
class VoldemortStorageBackendSuite extends FunSuite with ShouldMatchers with EmbeddedVoldemort with Logging {
  test("that ref storage and retrieval works") {
    val key = "testRef"
    val value = "testRefValue"
    val valueBytes = bytes(value)
    refAccess.delete(key.getBytes)
    refAccess.getValue(key.getBytes, empty) should be(empty)
    refAccess.put(key.getBytes, valueBytes)
    refAccess.getValue(key.getBytes) should be(valueBytes)
  }

  test("PersistentRef apis function as expected") {
    val key = "apiTestRef"
    val value = "apiTestRefValue"
    val valueBytes = bytes(value)
    refAccess.delete(key.getBytes)
    getRefStorageFor(key) should be(None)
    insertRefStorageFor(key, valueBytes)
    getRefStorageFor(key).get should equal(valueBytes)
  }

  test("that map key storage and retrieval works") {
    val key = "testmapKey"
    val mapKeys = new TreeSet[Array[Byte]] + bytes("key1")
    mapAccess.delete(getKey(key, mapKeysIndex))
    mapAccess.getValue(getKey(key, mapKeysIndex), SortedSetSerializer.toBytes(emptySet)) should equal(SortedSetSerializer.toBytes(emptySet))
    putMapKeys(key, mapKeys)
    getMapKeys(key) should equal(mapKeys)
  }

  test("that map value storage and retrieval works") {
    val key = bytes("keyForTestingMapValueClient")
    val value = bytes("value for testing map value client")
    mapAccess.put(key, value)
    mapAccess.getValue(key, empty) should equal(value)
  }


  test("PersistentMap apis function as expected") {
    val name = "theMap"
    val key = bytes("mapkey")
    val value = bytes("mapValue")
    removeMapStorageFor(name, key)
    removeMapStorageFor(name)
    getMapStorageEntryFor(name, key) should be(None)
    getMapStorageSizeFor(name) should be(0)
    getMapStorageFor(name).length should be(0)
    getMapStorageRangeFor(name, None, None, 100).length should be(0)

    insertMapStorageEntryFor(name, key, value)

    getMapStorageEntryFor(name, key).get should equal(value)
    getMapStorageSizeFor(name) should be(1)
    getMapStorageFor(name).length should be(1)
    getMapStorageRangeFor(name, None, None, 100).length should be(1)

    removeMapStorageFor(name, key)
    removeMapStorageFor(name)
    getMapStorageEntryFor(name, key) should be(None)
    getMapStorageSizeFor(name) should be(0)
    getMapStorageFor(name).length should be(0)
    getMapStorageRangeFor(name, None, None, 100).length should be(0)

    insertMapStorageEntriesFor(name, List(key -> value))

    getMapStorageEntryFor(name, key).get should equal(value)
    getMapStorageSizeFor(name) should be(1)
    getMapStorageFor(name).length should be(1)
    getMapStorageRangeFor(name, None, None, 100).length should be(1)

  }


  test("that vector value storage and retrieval works") {
    val key = "vectorValueKey"
    val index = 3
    val value = bytes("some bytes")
    val vecKey = getIndexedKey(key, index)
    getIndexFromVectorValueKey(key, vecKey) should be(index)
    vectorAccess.delete(vecKey)
    vectorAccess.getValue(vecKey, empty) should equal(empty)
    vectorAccess.put(vecKey, value)
    vectorAccess.getValue(vecKey) should equal(value)
  }

  test("PersistentVector apis function as expected") {
    val key = "vectorApiKey"
    val value = bytes("Some bytes we want to store in a vector")
    val updatedValue = bytes("Some updated bytes we want to store in a vector")
    vectorAccess.deleteIndexed(key, vectorHeadIndex)
    vectorAccess.deleteIndexed(key, vectorTailIndex)
    vectorAccess.delete(getIndexedKey(key, 0))
    vectorAccess.delete(getIndexedKey(key, 1))

    insertVectorStorageEntryFor(key, value)
    //again
    insertVectorStorageEntryFor(key, value)

    getVectorStorageEntryFor(key, 0) should be(value)
    getVectorStorageEntryFor(key, 1) should be(value)
    getVectorStorageRangeFor(key, None, None, 1).head should be(value)
    getVectorStorageRangeFor(key, Some(1), None, 1).head should be(value)
    getVectorStorageSizeFor(key) should be(2)

    updateVectorStorageEntryFor(key, 1, updatedValue)

    getVectorStorageEntryFor(key, 0) should be(value)
    getVectorStorageEntryFor(key, 1) should be(updatedValue)
    getVectorStorageRangeFor(key, None, None, 1).head should be(value)
    getVectorStorageRangeFor(key, Some(1), None, 1).head should be(updatedValue)
    getVectorStorageSizeFor(key) should be(2)

  }

  test("Persistent Queue apis function as expected") {
    val key = "queueApiKey"
    val value = bytes("some bytes even")
    val valueOdd = bytes("some bytes odd")

    remove(key)
    VoldemortStorageBackend.size(key) should be(0)
    enqueue(key, value) should be(Some(1))
    VoldemortStorageBackend.size(key) should be(1)
    enqueue(key, valueOdd) should be(Some(2))
    VoldemortStorageBackend.size(key) should be(2)
    peek(key, 0, 1)(0) should be(value)
    peek(key, 1, 1)(0) should be(valueOdd)
    dequeue(key).get should be(value)
    VoldemortStorageBackend.size(key) should be(1)
    dequeue(key).get should be(valueOdd)
    VoldemortStorageBackend.size(key) should be(0)
    dequeue(key) should be(None)
    queueAccess.putIndexed(key,queueHeadIndex, IntSerializer.toBytes(Integer.MAX_VALUE))
    queueAccess.putIndexed(key, queueTailIndex, IntSerializer.toBytes(Integer.MAX_VALUE))
    VoldemortStorageBackend.size(key) should be(0)
    enqueue(key, value) should be(Some(1))
    VoldemortStorageBackend.size(key) should be(1)
    enqueue(key, valueOdd) should be(Some(2))
    VoldemortStorageBackend.size(key) should be(2)
    peek(key, 0, 1)(0) should be(value)
    peek(key, 1, 1)(0) should be(valueOdd)
    dequeue(key).get should be(value)
    VoldemortStorageBackend.size(key) should be(1)
    dequeue(key).get should be(valueOdd)
    VoldemortStorageBackend.size(key) should be(0)
    dequeue(key) should be(None)


  }

  def getIndexFromVectorValueKey(owner: String, key: Array[Byte]): Int = {
    val indexBytes = new Array[Byte](IntSerializer.bytesPerInt)
    System.arraycopy(key, key.length - IntSerializer.bytesPerInt, indexBytes, 0, IntSerializer.bytesPerInt)
    IntSerializer.fromBytes(indexBytes)
  }


}

object VoldemortStorageBackendSuite {
  val empty = Array.empty[Byte]
  val emptySet = new TreeSet[Array[Byte]]

  def bytes(value: String): Array[Byte] = {
    value.getBytes("UTF-8")
  }

}
