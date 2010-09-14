/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.voldemort

import se.scalablesolutions.akka.util.UUID
import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import voldemort.client.StoreClient


class VoldemortSession {

  val voldemort: StoreClient

  def getOptionalBytes(name: String): Option[Array[Byte]] = {
  
  }

  def put(name:)


}