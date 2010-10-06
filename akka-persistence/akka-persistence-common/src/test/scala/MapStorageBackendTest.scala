/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}
import scala.util.Random
import collection.immutable.{TreeMap, HashMap, HashSet}
import se.scalablesolutions.akka.persistence.common.PersistentMapBinary.COrdering._


/**
 * Implementation Compatibility test for PersistentMap backend implementations.
 */

trait MapStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: MapStorageBackend[Array[Byte], Array[Byte]]

  def dropMaps: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping maps")
    dropMaps
  }

  override def afterEach = {
    log.info("afterEach: dropping maps")
    dropMaps
  }


  describe("A Properly functioning MapStorageBackend") {
    it("should remove map storage properly") {
      val mapName = "removeTest"
      val mkey = "removeTestKey".getBytes
      val value = "removeTestValue".getBytes

      storage.insertMapStorageEntryFor(mapName, mkey, value)
      storage.getMapStorageEntryFor(mapName, mkey).isDefined should be(true)
      storage.removeMapStorageFor(mapName, mkey)
      storage.getMapStorageEntryFor(mapName, mkey) should be(None)

      storage.insertMapStorageEntryFor(mapName, mkey, value)
      storage.getMapStorageEntryFor(mapName, mkey).isDefined should be(true)
      storage.removeMapStorageFor(mapName)
      storage.getMapStorageEntryFor(mapName, mkey) should be(None)
    }

    it("should insert a single map storage element properly") {
      val mapName = "insertSingleTest"
      val mkey = "insertSingleTestKey".getBytes
      val value = "insertSingleTestValue".getBytes

      storage.insertMapStorageEntryFor(mapName, mkey, value)
      storage.getMapStorageEntryFor(mapName, mkey).get should be(value)
      storage.removeMapStorageFor(mapName, mkey)
      storage.getMapStorageEntryFor(mapName, mkey) should be(None)

      storage.insertMapStorageEntryFor(mapName, mkey, value)
      storage.getMapStorageEntryFor(mapName, mkey).get should be(value)
      storage.removeMapStorageFor(mapName)
      storage.getMapStorageEntryFor(mapName, mkey) should be(None)
    }


    it("should insert multiple map storage elements properly") {
      val mapName = "insertMultipleTest"
      val rand = new Random(3).nextInt(100)
      val entries = (1 to rand).toList.map {
        index =>
          (("insertMultipleTestKey" + index).getBytes -> ("insertMutlipleTestValue" + index).getBytes)
      }

      storage.insertMapStorageEntriesFor(mapName, entries)
      entries foreach {
        _ match {
          case (mkey, value) => {
            storage.getMapStorageEntryFor(mapName, mkey).isDefined should be(true)
            storage.getMapStorageEntryFor(mapName, mkey).get should be(value)
          }
        }
      }
      storage.removeMapStorageFor(mapName)
      entries foreach {
        _ match {
          case (mkey, value) => {
            storage.getMapStorageEntryFor(mapName, mkey) should be(None)
          }
        }
      }
    }


    it("should accurately track the number of key value pairs in a map") {
      val mapName = "sizeTest"
      val rand = new Random(3).nextInt(100)
      val entries = (1 to rand).toList.map {
        index =>
          (("sizeTestKey" + index).getBytes -> ("sizeTestValue" + index).getBytes)
      }

      storage.insertMapStorageEntriesFor(mapName, entries)
      storage.getMapStorageSizeFor(mapName) should be(rand)
    }



    it("should return all the key value pairs in the map in the correct order when getMapStorageFor(name) is called") {
      val mapName = "allTest"
      val rand = new Random(3).nextInt(100)
      var entries = new TreeMap[Array[Byte], Array[Byte]]()(ArrayOrdering)
      (1 to rand).foreach {
        index =>
          entries += (("allTestKey" + index).getBytes -> ("allTestValue" + index).getBytes)
      }

      storage.insertMapStorageEntriesFor(mapName, entries.toList)
      val retrieved = storage.getMapStorageFor(mapName)
      retrieved.size should be(rand)
      entries.size should be(rand)



      val entryMap = new HashMap[String, String] ++ entries.map {_ match {case (k, v) => (new String(k), new String(v))}}
      val retrievedMap = new HashMap[String, String] ++ entries.map {_ match {case (k, v) => (new String(k), new String(v))}}

      entryMap should equal(retrievedMap)

      (0 until rand).foreach {
        i: Int => {
          new String(entries.toList(i)._1) should be(new String(retrieved(i)._1))
        }
      }

    }

    it("should return all the key->value pairs that exist in the map that are between start and end, up to count pairs when getMapStorageRangeFor is called") {
      //implement if this method will be used
    }


    it("should return Some(null), not None, for a key that has had the value null set and None for a key with no value set") {
      val mapName = "nullTest"
      val key = "key".getBytes
      storage.insertMapStorageEntryFor(mapName, key, null)
      storage.getMapStorageEntryFor(mapName, key).get should be(null)
      storage.removeMapStorageFor(mapName, key)
      storage.getMapStorageEntryFor(mapName, key) should be(None)
    }

    it("should not throw an exception when size is called on a non existent map?") {
      storage.getMapStorageSizeFor("nonExistent") should be(0)
    }


  }

}