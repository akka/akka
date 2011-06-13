package akka.cluster

object StorageTestUtils {

  def assertContent(key: String, expectedData: Array[Byte], expectedVersion: Long)(implicit storage: InMemoryStorage) {
    val found = storage.load(key)
    assert(found.version == expectedVersion)
    assert(expectedData == found.data) //todo: structural equals
  }

  def assertContent(key: String, expectedData: Array[Byte])(implicit storage: InMemoryStorage) {
    val found = storage.load(key)
    assert(expectedData == found.data) //todo: structural equals
  }
}