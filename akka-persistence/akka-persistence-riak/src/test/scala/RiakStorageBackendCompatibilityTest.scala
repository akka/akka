package se.scalablesolutions.akka.persistence.riak


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class RiakRefStorageBackendTestIntegration extends RefStorageBackendTest   {
  def dropRefs = {
    RiakStorageBackend.RefClient.drop
  }


  def storage = RiakStorageBackend
}

@RunWith(classOf[JUnitRunner])
class RiakMapStorageBackendTestIntegration extends MapStorageBackendTest   {
  def dropMaps = {
    RiakStorageBackend.MapClient.drop
  }


  def storage = RiakStorageBackend
}

@RunWith(classOf[JUnitRunner])
class RiakVectorStorageBackendTestIntegration extends VectorStorageBackendTest   {
  def dropVectors = {
    RiakStorageBackend.VectorClient.drop
  }


  def storage = RiakStorageBackend
}


@RunWith(classOf[JUnitRunner])
class RiakQueueStorageBackendTestIntegration extends QueueStorageBackendTest  {
  def dropQueues = {
    RiakStorageBackend.QueueClient.drop
  }


  def storage = RiakStorageBackend
}


