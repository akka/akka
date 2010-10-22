package se.scalablesolutions.akka.persistence.voldemort


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class VoldemortRefStorageBackendTest extends RefStorageBackendTest with EmbeddedVoldemort {
  def dropRefs = {
    VoldemortStorageBackend.refAccess.drop
  }


  def storage = VoldemortStorageBackend
}

@RunWith(classOf[JUnitRunner])
class VoldemortMapStorageBackendTest extends MapStorageBackendTest with EmbeddedVoldemort {
  def dropMaps = {
     VoldemortStorageBackend.mapAccess.drop
  }


  def storage = VoldemortStorageBackend
}

@RunWith(classOf[JUnitRunner])
class VoldemortVectorStorageBackendTest extends VectorStorageBackendTest with EmbeddedVoldemort {
  def dropVectors = {
    VoldemortStorageBackend.vectorAccess.drop
  }


  def storage = VoldemortStorageBackend
}


@RunWith(classOf[JUnitRunner])
class VoldemortQueueStorageBackendTest extends QueueStorageBackendTest with EmbeddedVoldemort {
  def dropQueues = {
    VoldemortStorageBackend.queueAccess.drop
  }


  def storage = VoldemortStorageBackend
}


