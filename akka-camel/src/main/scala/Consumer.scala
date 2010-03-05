/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import se.scalablesolutions.akka.actor.Actor

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer {

  self: Actor =>

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def endpointUri: String

}