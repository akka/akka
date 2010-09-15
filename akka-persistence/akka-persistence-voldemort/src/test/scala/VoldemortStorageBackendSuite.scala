package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.voldemort.VoldemortStorageBackend._
import se.scalablesolutions.akka.util.{Logging, UUID}
import collection.immutable.TreeSet
import VoldemortStorageBackendSuite._

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

  test("that map key storage and retrieval works") {
    val key = "testmapKey"
    val mapKeys = new TreeSet[Array[Byte]] + bytes("key1")
    mapKeyClient.delete(key)
    mapKeyClient.getValue(key, emptySet) should equal(emptySet)
    mapKeyClient.put(key, mapKeys)
    mapKeyClient.getValue(key, emptySet) should equal(mapKeys)

  }

  test("that map value storage and retrieval works") {
    val key = bytes("keyForTestingMapValueClient")
    val value = bytes("value for testing map value client")
    mapValueClient.put(key, value)
    mapValueClient.getValue(key, empty) should equal(value)
  }

  test("that vector size storage and retrieval works") {
    val key = "vectorKey"
    val size = IntSerializer.toBytes(17)
    vectorSizeClient.delete(key)
    vectorSizeClient.getValue(key, empty) should equal(empty)
    vectorSizeClient.put(key, size)
    vectorSizeClient.getValue(key) should equal(size)
  }

  test("that vector value storage and retrieval works") {
    val key = "vectorValueKey"
    val index = 3
    val value = bytes("some bytes")
    val vecKey = getVectorValueKey(key, index)
    getIndexFromVectorValueKey(key, vecKey) should be(index)
    vectorValueClient.delete(vecKey)
    vectorValueClient.getValue(vecKey, empty) should equal(empty)
    vectorValueClient.put(vecKey, value)
    vectorValueClient.getValue(vecKey) should equal(value)
  }

}

object VoldemortStorageBackendSuite {
  val empty = Array.empty[Byte]
  val emptySet = new TreeSet[Array[Byte]]

  def bytes(value: String): Array[Byte] = {
    value.getBytes("UTF-8")
  }

}