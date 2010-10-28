/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}
import scala.util.Random

/**
 * Implementation Compatibility test for PersistentVector backend implementations.
 */

trait VectorStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: VectorStorageBackend[Array[Byte]]

  def dropVectors: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping vectors")
    dropVectors
  }

  override def afterEach = {
    log.info("afterEach: dropping vectors")
    dropVectors
  }



  describe("A Properly functioning VectorStorageBackend") {
    it("should insertVectorStorageEntry as a logical prepend operation to the existing list") {
      val vector = "insertSingleTest"
      val rand = new Random(3).nextInt(100)
      val values = (0 to rand).toList.map {i: Int => vector + "value" + i}
      storage.getVectorStorageSizeFor(vector) should be(0)
      values.foreach {s: String => storage.insertVectorStorageEntryFor(vector, s.getBytes)}
      val shouldRetrieve = values.reverse
      (0 to rand).foreach {
        i: Int => {
          shouldRetrieve(i) should be(new String(storage.getVectorStorageEntryFor(vector, i)))
        }
      }
    }

    it("should insertVectorStorageEntries as a logical prepend operation to the existing list") {
      val vector = "insertMultiTest"
      val rand = new Random(3).nextInt(100)
      val values = (0 to rand).toList.map {i: Int => vector + "value" + i}
      storage.getVectorStorageSizeFor(vector) should be(0)
      storage.insertVectorStorageEntriesFor(vector, values.map {s: String => s.getBytes})
      val shouldRetrieve = values.reverse
      (0 to rand).foreach {
        i: Int => {
          shouldRetrieve(i) should be(new String(storage.getVectorStorageEntryFor(vector, i)))
        }
      }
    }

    it("should successfully update entries") {
      val vector = "updateTest"
      val rand = new Random(3).nextInt(100)
      val values = (0 to rand).toList.map {i: Int => vector + "value" + i}
      val urand = new Random(3).nextInt(rand)
      storage.insertVectorStorageEntriesFor(vector, values.map {s: String => s.getBytes})
      val toUpdate = "updated" + values.reverse(urand)
      storage.updateVectorStorageEntryFor(vector, urand, toUpdate.getBytes)
      toUpdate should be(new String(storage.getVectorStorageEntryFor(vector, urand)))
    }

    it("should return the correct value from getVectorStorageFor") {
      val vector = "getTest"
      val rand = new Random(3).nextInt(100)
      val values = (0 to rand).toList.map {i: Int => vector + "value" + i}
      val urand = new Random(3).nextInt(rand)
      storage.insertVectorStorageEntriesFor(vector, values.map {s: String => s.getBytes})
      values.reverse(urand) should be(new String(storage.getVectorStorageEntryFor(vector, urand)))
    }

    it("should return the correct values from getVectorStorageRangeFor") {
      val vector = "getTest"
      val rand = new Random(3).nextInt(100)
      val drand = new Random(3).nextInt(rand)
      val values = (0 to rand).toList.map {i: Int => vector + "value" + i}
      storage.insertVectorStorageEntriesFor(vector, values.map {s: String => s.getBytes})
      values.reverse should be(storage.getVectorStorageRangeFor(vector, None, None, rand + 1).map {b: Array[Byte] => new String(b)})
      (0 to drand).foreach {
        i: Int => {
          val value: String = vector + "value" + (rand - i)
          log.debug(value)
          List(value) should be(storage.getVectorStorageRangeFor(vector, Some(i), None, 1).map {b: Array[Byte] => new String(b)})
        }
      }
    }

    it("should behave properly when the range used in getVectorStorageRangeFor has indexes outside the current size of the vector") {
      //what is proper?
    }

    it("shoud return null when getStorageEntry is called on a null entry") {
      //What is proper?
      val vector = "nullTest"
      storage.insertVectorStorageEntryFor(vector, null)
      storage.getVectorStorageEntryFor(vector, 0) should be(null)
    }

    it("shoud throw a Storage exception when there is an attempt to retrieve an index larger than the Vector") {
      val vector = "tooLargeRetrieve"
      storage.insertVectorStorageEntryFor(vector, null)
      evaluating {storage.getVectorStorageEntryFor(vector, 9)} should produce[StorageException]
    }

    it("shoud throw a Storage exception when there is an attempt to update an index larger than the Vector") {
      val vector = "tooLargeUpdate"
      storage.insertVectorStorageEntryFor(vector, null)
      evaluating {storage.updateVectorStorageEntryFor(vector, 9, null)} should produce[StorageException]
    }

  }

}