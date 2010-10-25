/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.voldemort


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.common._

@RunWith(classOf[JUnitRunner])
class VoldemortTicket343Test extends Ticket343Test with EmbeddedVoldemort {
  def dropMapsAndVectors: Unit = {
    VoldemortStorageBackend.mapAccess.drop
    VoldemortStorageBackend.vectorAccess.drop
  }

  def getVector: (String) => PersistentVector[Array[Byte]] = VoldemortStorage.getVector

  def getMap: (String) => PersistentMap[Array[Byte], Array[Byte]] = VoldemortStorage.getMap

}