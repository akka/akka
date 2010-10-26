package se.scalablesolutions.akka.persistence.cassandra


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common.{VectorStorageBackendTest, MapStorageBackendTest, RefStorageBackendTest}

@RunWith(classOf[JUnitRunner])
class CassandraRefStorageBackendTestIntegration extends RefStorageBackendTest {
  def dropRefs = {
    //
  }


  def storage = CassandraStorageBackend
}

@RunWith(classOf[JUnitRunner])
class CassandraMapStorageBackendTestIntegration extends MapStorageBackendTest {
  def dropMaps = {
    //
  }


  def storage = CassandraStorageBackend
}

@RunWith(classOf[JUnitRunner])
class CassandraVectorStorageBackendTestIntegration extends VectorStorageBackendTest {
  def dropVectors = {
    //
  }


  def storage = CassandraStorageBackend
}





