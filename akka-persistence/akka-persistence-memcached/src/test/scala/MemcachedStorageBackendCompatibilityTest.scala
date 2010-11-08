package akka.persistence.memcached


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class MemcachedRefStorageBackendTestIntegration extends RefStorageBackendTest   {
  def dropRefs = {
    MemcachedStorageBackend.refAccess.drop
  }


  def storage = MemcachedStorageBackend
}

@RunWith(classOf[JUnitRunner])
class MemcachedMapStorageBackendTestIntegration extends MapStorageBackendTest   {
  def dropMaps = {
    MemcachedStorageBackend.mapAccess.drop
  }


  def storage = MemcachedStorageBackend
}

@RunWith(classOf[JUnitRunner])
class MemcachedVectorStorageBackendTestIntegration extends VectorStorageBackendTest   {
  def dropVectors = {
    MemcachedStorageBackend.vectorAccess.drop
  }


  def storage = MemcachedStorageBackend
}


@RunWith(classOf[JUnitRunner])
class MemcachedQueueStorageBackendTestIntegration extends QueueStorageBackendTest  {
  def dropQueues = {
    MemcachedStorageBackend.queueAccess.drop
  }


  def storage = MemcachedStorageBackend
}


