package akka.persistence.simpledb


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class SimpledbRefStorageBackendTestIntegration extends RefStorageBackendTest   {
  def dropRefs = {
    SimpledbStorageBackend.refAccess.drop
  }


  def storage = SimpledbStorageBackend
}

@RunWith(classOf[JUnitRunner])
class SimpledbMapStorageBackendTestIntegration extends MapStorageBackendTest   {
  def dropMaps = {
    SimpledbStorageBackend.mapAccess.drop
  }


  def storage = SimpledbStorageBackend
}

@RunWith(classOf[JUnitRunner])
class SimpledbVectorStorageBackendTestIntegration extends VectorStorageBackendTest   {
  def dropVectors = {
    SimpledbStorageBackend.vectorAccess.drop
  }


  def storage = SimpledbStorageBackend
}


@RunWith(classOf[JUnitRunner])
class SimpledbQueueStorageBackendTestIntegration extends QueueStorageBackendTest  {
  def dropQueues = {
    SimpledbStorageBackend.queueAccess.drop
  }


  def storage = SimpledbStorageBackend
}


