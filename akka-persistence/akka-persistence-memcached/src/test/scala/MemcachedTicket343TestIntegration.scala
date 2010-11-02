/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.memcached


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common._

@RunWith(classOf[JUnitRunner])
class MemcachedTicket343TestIntegration extends Ticket343Test  {
  def dropMapsAndVectors: Unit = {
    MemcachedStorageBackend.vectorAccess.drop
    MemcachedStorageBackend.mapAccess.drop
  }

  def getVector: (String) => PersistentVector[Array[Byte]] = MemcachedStorage.getVector

  def getMap: (String) => PersistentMap[Array[Byte], Array[Byte]] = MemcachedStorage.getMap

}
