package akka.cluster

import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.cluster.StorageTestUtils._

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
      val stored = storage.insert(key, value)

      val result = storage.load(key, stored.version)
      assert(result.version == stored.version)
      assert(result.data == stored.data)
    }

    "throw VersioningException is version too new" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val stored = storage.insert(key, value)

      try {
        storage.load(key, stored.version + 1)
        fail()
      } catch {
        case e: VersioningException ⇒
      }
    }

    "throw VersioningException is version too old" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      val stored = storage.insert(key, value)

      try {
        storage.load(key, stored.version - 1)
        fail()
      } catch {
        case e: VersioningException ⇒
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
      val oldValue = "oldvalue".getBytes

      val oldVersionedData = storage.insert(key, oldValue)

      val newValue = "newValue".getBytes

      try {
        storage.insert(key, newValue)
        fail()
      } catch {
        case e: DataExistsException ⇒
      }

      //make sure that the old value was not changed
      assert(oldVersionedData == storage.load(key))
    }
  }

  "update" must {

    "throw MissingDataException when no node exists" in {
      val storage = new InMemoryStorage()

      val key = "somekey"

      try {
        storage.update(key, new VersionedData("somevalue".getBytes, 1))
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
      val insert = storage.insert(key, oldValue)

      //do the update the will be the cause of the conflict.
      val updateValue = "update".getBytes
      val update = insert.createUpdate(updateValue)
      storage.update(key, update)

      assertContent(key, update.data, update.version)(storage)
    }

    "throw VersioningException when already overwritten" in {
      val storage = new InMemoryStorage()

      //do the initial insert
      val key = "foo"
      val oldValue = "insert".getBytes
      val insert = storage.insert(key, oldValue)

      //do the update the will be the cause of the conflict.
      val otherUpdateValue = "otherupdate".getBytes
      val otherUpdate = insert.createUpdate(otherUpdateValue)
      storage.update(key, otherUpdate)

      val update = insert.createUpdate("update".getBytes)

      try {
        storage.update(key, update)
        fail()
      } catch {
        case e: VersioningException ⇒
      }

      assertContent(key, otherUpdate.data, otherUpdate.version)(storage)
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
      val newValue: Array[Byte] = "somevalue".getBytes

      val initialInsert: VersionedData = storage.insert(key, oldValue)

      val result: VersionedData = storage.overwrite(key, newValue)

      assert(result.version == initialInsert.version + 1)
      assert(result.data == newValue)
      storage.load(key) must be eq (result)
    }
  }

  "insertOrOverwrite" must {
    "insert if nothing was inserted before" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes

      val result = storage.insertOrOverwrite(key, value)

      assert(result.version == InMemoryStorage.InitialVersion)
      assert(result.data == value)
      storage.load(key) must be eq (result)
    }

    "overwrite of something existed before" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val oldValue = "oldvalue".getBytes
      val newValue = "somevalue".getBytes

      val initialInsert = storage.insert(key, oldValue)

      val result = storage.insertOrOverwrite(key, newValue)

      assert(result.version == initialInsert.version + 1)
      assert(result.data == newValue)
      storage.load(key) must be eq (result)
    }
  }

}