package se.scalablesolutions.akka.persistence.voldemort


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class VoldemortRefStorageBackendTest extends RefStorageBackendTest with EmbeddedVoldemort {
  def dropRefs = {
    //drop Refs Impl
  }


  def storage = VoldemortStorageBackend
}

@RunWith(classOf[JUnitRunner])
class VoldemortMapStorageBackendTest extends MapStorageBackendTest with EmbeddedVoldemort {
  def dropMaps = {
    //drop Maps Impl
  }


  def storage = VoldemortStorageBackend
}

@RunWith(classOf[JUnitRunner])
class VoldemortVectorStorageBackendTest extends VectorStorageBackendTest with EmbeddedVoldemort {
  def dropVectors = {
    //drop Maps Impl
  }


  def storage = VoldemortStorageBackend
}


@RunWith(classOf[JUnitRunner])
class VoldemortQueueStorageBackendTest extends QueueStorageBackendTest with EmbeddedVoldemort {
  def dropQueues = {
    //drop Maps Impl
  }


  def storage = VoldemortStorageBackend
}


