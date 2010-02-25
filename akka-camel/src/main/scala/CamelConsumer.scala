/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import se.scalablesolutions.akka.actor.Actor

/**
 * Mixed in by Actor subclasses to be Camel endpoint consumers.
 *
 * @author Martin Krasser
 */
trait CamelConsumer {

  self: Actor =>

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def endpointUri: String

}