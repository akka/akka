package se.scalablesolutions.akka.persistence.voldemort


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class VoldemortRefStorageBackendTest extends RefStorageBackendTest with EmbeddedVoldemort {
  def dropRefs = {
    admin.truncate(0, VoldemortStorageBackend.refStore)
  }


  def storage = VoldemortStorageBackend
}

@RunWith(classOf[JUnitRunner])
class VoldemortMapStorageBackendTest extends MapStorageBackendTest with EmbeddedVoldemort {
  def dropMaps = {
    admin.truncate(0, VoldemortStorageBackend.mapStore)
  }


  def storage = VoldemortStorageBackend
}

@RunWith(classOf[JUnitRunner])
class VoldemortVectorStorageBackendTest extends VectorStorageBackendTest with EmbeddedVoldemort {
  def dropVectors = {
    admin.truncate(0, VoldemortStorageBackend.vectorStore)
  }


  def storage = VoldemortStorageBackend
}


@RunWith(classOf[JUnitRunner])
class VoldemortQueueStorageBackendTest extends QueueStorageBackendTest with EmbeddedVoldemort {
  def dropQueues = {
    admin.truncate(0, VoldemortStorageBackend.queueStore)
  }


  def storage = VoldemortStorageBackend
}


