/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.riak


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common._

@RunWith(classOf[JUnitRunner])
class RiakTicket343TestIntegration extends Ticket343Test  {
  def dropMapsAndVectors: Unit = {
    RiakStorageBackend.vectorAccess.drop
    RiakStorageBackend.mapAccess.drop
  }

  def getVector: (String) => PersistentVector[Array[Byte]] = RiakStorage.getVector

  def getMap: (String) => PersistentMap[Array[Byte], Array[Byte]] = RiakStorage.getMap

}