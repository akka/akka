package se.scalablesolutions.akka.persistence.riak


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class RiakRefStorageBackendTestIntegration extends RefStorageBackendTest   {
  def dropRefs = {
    RiakStorageBackend.refAccess.drop
  }


  def storage = RiakStorageBackend
}

@RunWith(classOf[JUnitRunner])
class RiakMapStorageBackendTestIntegration extends MapStorageBackendTest   {
  def dropMaps = {
    RiakStorageBackend.mapAccess.drop
  }


  def storage = RiakStorageBackend
}

@RunWith(classOf[JUnitRunner])
class RiakVectorStorageBackendTestIntegration extends VectorStorageBackendTest   {
  def dropVectors = {
    RiakStorageBackend.vectorAccess.drop
  }


  def storage = RiakStorageBackend
}


@RunWith(classOf[JUnitRunner])
class RiakQueueStorageBackendTestIntegration extends QueueStorageBackendTest  {
  def dropQueues = {
    RiakStorageBackend.queueAccess.drop
  }


  def storage = RiakStorageBackend
}


