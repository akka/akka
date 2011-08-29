package akka.cluster.storage

import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.cluster.storage.StorageTestUtils._

class InMemoryStorageSpec extends WordSpec with MustMatchers {

  "unversioned load" must {
    "throw MissingDataException if non existing key" in {
      val store = new InMemoryStorage()

      try {
        store.load("foo")
        fail()
      } catch {
        case e: MissingDataException ⇒
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
    "throw MissingDataException if non existing key" in {
      val store = new InMemoryStorage()

      try {
        store.load("foo", 1)
        fail()
      } catch {
        case e: MissingDataException ⇒
      }
    }

    "return VersionedData if key existing and exact version match" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val storedVersion = storage.insert(key, value)

      val loaded = storage.load(key, storedVersion)
      assert(loaded.version == storedVersion)
      org.junit.Assert.assertArrayEquals(value, loaded.data)
    }

    "throw BadVersionException is version too new" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val version = storage.insert(key, value)

      try {
        storage.load(key, version + 1)
        fail()
      } catch {
        case e: BadVersionException ⇒
      }
    }

    "throw BadVersionException is version too old" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val version = storage.insert(key, value)

      try {
        storage.load(key, version - 1)
        fail()
      } catch {
        case e: BadVersionException ⇒
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

    "throw MissingDataException when there already exists an entry with the same key" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val initialValue = "oldvalue".getBytes
      val initialVersion = storage.insert(key, initialValue)

      val newValue = "newValue".getBytes

      try {
        storage.insert(key, newValue)
        fail()
      } catch {
        case e: DataExistsException ⇒
      }

      assertContent(key, initialValue, initialVersion)(storage)
    }
  }

  "update" must {

    "throw MissingDataException when no node exists" in {
      val storage = new InMemoryStorage()

      val key = "somekey"

      try {
        storage.update(key, "somevalue".getBytes, 1)
        fail()
      } catch {
        case e: MissingDataException ⇒
      }
    }

    "replace if previous value exists and no other updates have been done" in {
      val storage = new InMemoryStorage()

      //do the initial insert
      val key = "foo"
      val oldValue = "insert".getBytes
      val initialVersion = storage.insert(key, oldValue)

      //do the update the will be the cause of the conflict.
      val newValue: Array[Byte] = "update".getBytes
      val newVersion = storage.update(key, newValue, initialVersion)

      assertContent(key, newValue, newVersion)(storage)
    }

    "throw BadVersionException when already overwritten" in {
      val storage = new InMemoryStorage()

      //do the initial insert
      val key = "foo"
      val oldValue = "insert".getBytes
      val initialVersion = storage.insert(key, oldValue)

      //do the update the will be the cause of the conflict.
      val newValue = "otherupdate".getBytes
      val newVersion = storage.update(key, newValue, initialVersion)

      try {
        storage.update(key, "update".getBytes, initialVersion)
        fail()
      } catch {
        case e: BadVersionException ⇒
      }

      assertContent(key, newValue, newVersion)(storage)
    }
  }

  "overwrite" must {

    "throw MissingDataException when no node exists" in {
      val storage = new InMemoryStorage()
      val key = "somekey"

      try {
        storage.overwrite(key, "somevalue".getBytes)
        fail()
      } catch {
        case e: MissingDataException ⇒
      }

      storage.exists(key) must be(false)
    }

    "succeed if previous value exist" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val oldValue = "oldvalue".getBytes
      val newValue = "somevalue".getBytes

      val initialVersion = storage.insert(key, oldValue)
      val overwriteVersion = storage.overwrite(key, newValue)

      assert(overwriteVersion == initialVersion + 1)
      assertContent(key, newValue, overwriteVersion)(storage)
    }
  }

  "insertOrOverwrite" must {
    "insert if nothing was inserted before" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes

      val version = storage.insertOrOverwrite(key, value)

      assert(version == InMemoryStorage.InitialVersion)
      assertContent(key, value, version)(storage)
    }

    "overwrite of something existed before" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val oldValue = "oldvalue".getBytes
      val newValue = "somevalue".getBytes

      val initialVersion = storage.insert(key, oldValue)

      val overwriteVersion = storage.insertOrOverwrite(key, newValue)

      assert(overwriteVersion == initialVersion + 1)
      assertContent(key, newValue, overwriteVersion)(storage)
    }
  }

}
