package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.voldemort.VoldemortStorageBackend._
import se.scalablesolutions.akka.util.{Logging}
import collection.immutable.TreeSet
import VoldemortStorageBackendSuite._
import scala.None

@RunWith(classOf[JUnitRunner])
class VoldemortStorageBackendSuite extends FunSuite with ShouldMatchers with EmbeddedVoldemort with Logging {
  test("that ref storage and retrieval works") {
    val key = "testRef"
    val value = "testRefValue"
    val valueBytes = bytes(value)
    refClient.delete(key)
    refClient.getValue(key, empty) should be(empty)
    refClient.put(key, valueBytes)
    refClient.getValue(key) should be(valueBytes)
  }

  test("PersistentRef apis function as expected") {
    val key = "apiTestRef"
    val value = "apiTestRefValue"
    val valueBytes = bytes(value)
    refClient.delete(key)
    getRefStorageFor(key) should be(None)
    insertRefStorageFor(key, valueBytes)
    getRefStorageFor(key).get should equal(valueBytes)
  }

  test("that map key storage and retrieval works") {
    val key = "testmapKey"
    val mapKeys = new TreeSet[Array[Byte]] + bytes("key1")
    mapClient.delete(getKey(key, mapKeysIndex))
    mapClient.getValue(getKey(key, mapKeysIndex), SortedSetSerializer.toBytes(emptySet)) should equal(SortedSetSerializer.toBytes(emptySet))
    putMapKeys(key, mapKeys)
    getMapKeys(key) should equal(mapKeys)
  }

  test("that map value storage and retrieval works") {
    val key = bytes("keyForTestingMapValueClient")
    val value = bytes("value for testing map value client")
    mapClient.put(key, value)
    mapClient.getValue(key, empty) should equal(value)
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
    vectorClient.delete(vecKey)
    vectorClient.getValue(vecKey, empty) should equal(empty)
    vectorClient.put(vecKey, value)
    vectorClient.getValue(vecKey) should equal(value)
  }

  test("PersistentVector apis function as expected") {
    val key = "vectorApiKey"
    val value = bytes("Some bytes we want to store in a vector")
    val updatedValue = bytes("Some updated bytes we want to store in a vector")
    vectorClient.delete(getKey(key, vectorSizeIndex))
    vectorClient.delete(getIndexedKey(key, 0))
    vectorClient.delete(getIndexedKey(key, 1))
    getVectorStorageEntryFor(key, 0) should be(empty)
    getVectorStorageEntryFor(key, 1) should be(empty)
    getVectorStorageRangeFor(key, None, None, 1).head should be(empty)

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
    dequeue(key).get should be(value)
    VoldemortStorageBackend.size(key) should be(1)
    dequeue(key).get should be(valueOdd)
    VoldemortStorageBackend.size(key) should be(0)
    dequeue(key) should be(None)
    queueClient.put(getKey(key, queueHeadIndex), IntSerializer.toBytes(Integer.MAX_VALUE))
    queueClient.put(getKey(key, queueTailIndex), IntSerializer.toBytes(Integer.MAX_VALUE))
    VoldemortStorageBackend.size(key) should be(0)
    enqueue(key, value) should be(Some(1))
    VoldemortStorageBackend.size(key) should be(1)
    enqueue(key, valueOdd) should be(Some(2))
    VoldemortStorageBackend.size(key) should be(2)
    dequeue(key).get should be(value)
    VoldemortStorageBackend.size(key) should be(1)
    dequeue(key).get should be(valueOdd)
    VoldemortStorageBackend.size(key) should be(0)
    dequeue(key) should be(None)


  }

}

object VoldemortStorageBackendSuite {
  val empty = Array.empty[Byte]
  val emptySet = new TreeSet[Array[Byte]]

  def bytes(value: String): Array[Byte] = {
    value.getBytes("UTF-8")
  }

}