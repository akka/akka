package akka.cluster.storage

object StorageTestUtils {

  def assertContent(key: String, expectedData: Array[Byte], expectedVersion: Long)(implicit storage: Storage) {
    val found = storage.load(key)
    assert(found.version == expectedVersion, "versions should match, found[" + found.version + "], expected[" + expectedVersion + "]")
    org.junit.Assert.assertArrayEquals(expectedData, found.data)
  }

  def assertContent(key: String, expectedData: Array[Byte])(implicit storage: Storage) {
    val found = storage.load(key)
    org.junit.Assert.assertArrayEquals(expectedData, found.data)
  }
}
