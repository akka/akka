/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.Logging
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

    

    //getStorageEntry for a non existent entry?
  }

}