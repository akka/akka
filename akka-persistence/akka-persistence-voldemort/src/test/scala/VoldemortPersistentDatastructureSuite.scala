package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.voldemort.VoldemortStorageBackend._
import se.scalablesolutions.akka.util.{Logging, UUID}
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
    val name = UUID.newUuid.toString
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


}