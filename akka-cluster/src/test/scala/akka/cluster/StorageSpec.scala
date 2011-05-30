package akka.cluster

import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec

class InMemoryStorageSpec extends WordSpec with MustMatchers {

  "unversioned load" must {
    "throw MissingNodeException if non existing key" in {
      val store = new InMemoryStorage()

      try {
        store.load("foo")
        fail()
      } catch {
        case e: MissingNodeException ⇒
      }
    }

    "return VersionedData if key existing" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      storage.insert(key, value)

      val result = storage.load(key)
      //todo: strange that the implicit store is not found
      assertContent(key, value, result.version)(storage)
    }
  }

  "exist" must {
    "return true if value exists" in {
      val store = new InMemoryStorage()
      val key = "somekey"
      store.insert(key, "somevalue".getBytes)
      store.exists(key) must be(true)
    }

    "return false if value not exists" in {
      val store = new InMemoryStorage()
      store.exists("somekey") must be(false)
    }
  }

  "versioned load" must {
    "throw MissingNodeException if non existing key" in {
      val store = new InMemoryStorage()

      try {
        store.load("foo", 1)
        fail()
      } catch {
        case e: MissingNodeException ⇒
      }
    }

    "return VersionedData if key existing and exact version match" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val stored = storage.insert(key, value)

      val result = storage.load(key, stored.version)
      assert(result.version == stored.version)
      assert(result.data == stored.data)
    }

    "throw VersioningMismatchStorageException is version too new" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val stored = storage.insert(key, value)

      try {
        storage.load(key, stored.version + 1)
        fail()
      } catch {
        case e: VersioningMismatchStorageException ⇒
      }
    }

    "throw VersioningMismatchStorageException is version too old" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val stored = storage.insert(key, value)

      try {
        storage.load(key, stored.version - 1)
        fail()
      } catch {
        case e: VersioningMismatchStorageException ⇒
      }
    }
  }

  "insert" must {

    "place a new value when non previously existed" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val oldValue = "oldvalue".getBytes
      storage.insert(key, oldValue)

      val result = storage.load(key)
      assertContent(key, oldValue)(storage)
      assert(InMemoryStorage.InitialVersion == result.version)
    }

    "throw MissingNodeException when there already exists an entry with the same key" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val oldValue = "oldvalue".getBytes

      val oldVersionedData = storage.insert(key, oldValue)

      val newValue = "newValue".getBytes

      try {
        storage.insert(key, newValue)
        fail()
      } catch {
        case e: NodeExistsException ⇒
      }

      //make sure that the old value was not changed
      assert(oldVersionedData == storage.load(key))
    }
  }

  "update with versioning" must {

    "throw NoNodeException when no node exists" in {
      val storage = new InMemoryStorage()

      val key = "somekey"

      try {
        storage.update(key, new VersionedData("somevalue".getBytes, 1))
        fail()
      } catch {
        case e: MissingNodeException ⇒
      }
    }

    "throw OptimisticLockException when ..." in {

    }

    "replace" in {
    }
  }

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