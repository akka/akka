package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.voldemort.VoldemortStorageBackend._
import se.scalablesolutions.akka.actor.{newUuid, Uuid}
import collection.immutable.TreeSet
import VoldemortStorageBackendSuite._

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config.config

@RunWith(classOf[JUnitRunner])
class VoldemortPersistentDatastructureSuite extends FunSuite with ShouldMatchers with EmbeddedVoldemort with Logging {
  test("persistentRefs work as expected") {
    val name = newUuid.toString
    val one = "one".getBytes
    atomic {
      val ref = VoldemortStorage.getRef(name)
      ref.isDefined should be(false)
      ref.swap(one)
      ref.get match {
        case Some(bytes) => bytes should be(one)
        case None => true should be(false)
      }
    }
    val two = "two".getBytes
    atomic {
      val ref = VoldemortStorage.getRef(name)
      ref.isDefined should be(true)
      ref.swap(two)
      ref.get match {
        case Some(bytes) => bytes should be(two)
        case None => true should be(false)
      }
    }
  }


  test("Persistent Vectors function as expected") {
    val name = newUuid.toString
    val one = "one".getBytes
    val two = "two".getBytes
    atomic {
      val vec = VoldemortStorage.getVector(name)
      vec.add(one)
    }
    atomic {
      val vec = VoldemortStorage.getVector(name)
      vec.size should be(1)
      vec.add(two)
    }
    atomic {
      val vec = VoldemortStorage.getVector(name)

      vec.get(0) should be(one)
      vec.get(1) should be(two)
      vec.size should be(2)
      vec.update(0, two)
    }

    atomic {
      val vec = VoldemortStorage.getVector(name)
      vec.get(0) should be(two)
      vec.get(1) should be(two)
      vec.size should be(2)
      vec.update(0, Array.empty[Byte])
      vec.update(1, Array.empty[Byte])
    }

    atomic {
      val vec = VoldemortStorage.getVector(name)
      vec.get(0) should be(Array.empty[Byte])
      vec.get(1) should be(Array.empty[Byte])
      vec.size should be(2)
    }


  }

  test("Persistent Maps work as expected") {
    atomic {
      val map = VoldemortStorage.getMap("map")
      map.put("mapTest".getBytes, null)
    }

    atomic {
      val map = VoldemortStorage.getMap("map")
      map.get("mapTest".getBytes).get should be(null)
    }


  }

}