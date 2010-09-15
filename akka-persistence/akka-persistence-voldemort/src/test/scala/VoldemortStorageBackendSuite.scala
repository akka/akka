package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.voldemort.VoldemortStorageBackend._
import se.scalablesolutions.akka.util.{Logging, UUID}
import collection.immutable.TreeSet

@RunWith(classOf[JUnitRunner])
class VoldemortStorageBackendSuite extends FunSuite with ShouldMatchers with EmbeddedVoldemort with Logging {
  test("that ref storage and retrieval works") {
    refClient.put("testRef", "testRefValue".getBytes("UTF-8"))
    new String(refClient.getValue("testRef", Array.empty[Byte]), "UTF-8") should be("testRefValue")
  }

  test("that map key storage and retrieval works") {
    val mapKeys = new TreeSet[Array[Byte]] + "key1".getBytes
    mapKeyClient.put("testMapKey", mapKeys)
    val returned = mapKeyClient.getValue("testMapKey", new TreeSet[Array[Byte]])
    returned should equal(mapKeys)
  }

  test("that map value storage and retrieval works") {
    val key = "keyForTestingMapValueClient".getBytes("UTF-8")
    val value = "value for testing map value client".getBytes("UTF-8")
    mapValueClient.put(key, value)
    mapValueClient.getValue(key) should equal(value)
  }

  test("that vector size storage and retrieval works") {
    val key = "vectorKey"
    vectorSizeClient.put(key, IntSerializer.toBytes(17))
    vectorSizeClient.getValue(key) should equal(IntSerializer.toBytes(17))
  }

  test("that vector value storage and retrieval works") {
    val key = "vectorValueKey"
    val index = 3
    val value = "some bytes".getBytes("UTF-8")
    val vecKey = getVectorValueKey(key, index)
    try{
    val idx = getIndexFromVectorValueKey(key, vecKey)
    vectorValueClient.put(vecKey, value)
    vectorValueClient.get(vecKey) should equal(value)
    } catch{
      case e => e.printStackTrace
    }
  }

}