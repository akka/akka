/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

object UUID {
  def newUuid = new com.eaio.uuid.UUID()
}