/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.simpledb


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.persistence.common._

@RunWith(classOf[JUnitRunner])
class SimpledbTicket343TestIntegration extends Ticket343Test {
  def dropMapsAndVectors: Unit = {
    SimpledbStorageBackend.vectorAccess.drop
    SimpledbStorageBackend.mapAccess.drop
  }

  def getVector: (String) => PersistentVector[Array[Byte]] = SimpledbStorage.getVector

  def getMap: (String) => PersistentMap[Array[Byte], Array[Byte]] = SimpledbStorage.getMap

}
