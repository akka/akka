package se.scalablesolutions.akka.persistence.voldemort


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{RefStorageBackend, RefStorageBackendTest}
import org.scalatest.Spec


@RunWith(classOf[JUnitRunner])
class VoldemortStorageBackendCompatibilityTest extends RefStorageBackendTest with EmbeddedVoldemort {
  def dropRefs: Unit = {
    //drop Refs Impl
  }


  def storage: RefStorageBackend[Array[Byte]] = {VoldemortStorageBackend}
}