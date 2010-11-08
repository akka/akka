package akka.persistence.cassandra


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common.{QueueStorageBackendTest, VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class CassandraRefStorageBackendTestIntegration extends RefStorageBackendTest {
  def dropRefs = {
    CassandraStorageBackend.refAccess.drop
  }


  def storage = CassandraStorageBackend
}

@RunWith(classOf[JUnitRunner])
class CassandraMapStorageBackendTestIntegration extends MapStorageBackendTest {
  def dropMaps = {
    CassandraStorageBackend.mapAccess.drop
  }


  def storage = CassandraStorageBackend
}

@RunWith(classOf[JUnitRunner])
class CassandraVectorStorageBackendTestIntegration extends VectorStorageBackendTest {
  def dropVectors = {
    CassandraStorageBackend.vectorAccess.drop
  }


  def storage = CassandraStorageBackend
}

@RunWith(classOf[JUnitRunner])
class CassandraQueueStorageBackendTestIntegration extends QueueStorageBackendTest {
  def dropQueues = {
      CassandraStorageBackend.queueAccess.drop
  }


  def storage = CassandraStorageBackend
}





