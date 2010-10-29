/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.cassandra


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common._

@RunWith(classOf[JUnitRunner])
class CassandraTicket343TestIntegration extends Ticket343Test  {
  def dropMapsAndVectors: Unit = {
    CassandraStorageBackend.vectorAccess.drop
    CassandraStorageBackend.mapAccess.drop
  }

  def getVector: (String) => PersistentVector[Array[Byte]] = CassandraStorage.getVector

  def getMap: (String) => PersistentMap[Array[Byte], Array[Byte]] = CassandraStorage.getMap

}