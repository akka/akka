package akka.persistence.simpledb


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{BeforeAndAfterEach, Spec}

@RunWith(classOf[JUnitRunner])
class SimpledbTestIntegration extends Spec with ShouldMatchers with BeforeAndAfterEach {
  import SimpledbStorageBackend._


  describe("the limitations of the simpledb storage backend") {
    it("should store up to 255K per item base 64 encoded with a name+key length <= 1024 bytes base64 encoded") {
      val name = "123456"
      val keysize: Int = 758
      log.info("key:" + keysize)
      val key = new Array[Byte](keysize)
      val valsize: Int = 195840
      log.info("value:" + valsize)

      val value = new Array[Byte](valsize)
      mapAccess.put(name, key, value)
      val result = mapAccess.get(name, key, Array.empty[Byte])
      result.size should be(value.size)
      result should be(value)
    }

    it("should not accept a name+key longer that 1024 bytes base64 encoded") {
      val name = "fail"
      val key = new Array[Byte](2048)
      val value = new Array[Byte](1)
      evaluating {
        mapAccess.put(name, key, value)
      } should produce[IllegalArgumentException]
    }

    it("should not accept a value larger than 255K base 64 encoded") {
      val name = "failValue"
      val key = "failKey".getBytes
      val value = new Array[Byte](1024 * 512)
      evaluating {
        mapAccess.put(name, key, value)
      } should produce[IllegalArgumentException]
    }
  }

  override protected def beforeEach(): Unit = {
    mapAccess.drop
  }
}