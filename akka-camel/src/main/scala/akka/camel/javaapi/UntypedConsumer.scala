/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._
import org.apache.camel.{ ProducerTemplate, CamelContext }

/**
 *  Java-friendly Consumer.
 *
 * @see UntypedConsumerActor
 *
 * @author Martin Krasser
 */
trait UntypedConsumer extends Consumer { self: UntypedActor ⇒
  final def endpointUri = getEndpointUri

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

}

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 */
abstract class UntypedConsumerActor extends UntypedActor with UntypedConsumer {
  /**
   * Returns the [[org.apache.camel.CamelContext]]
   * @return the CamelContext
   */
  protected def getCamelContext: CamelContext = camelContext

  /**
   * Returns the [[org.apache.camel.ProducerTemplate]]
   * @return the ProducerTemplate
   */
  protected def getProducerTemplate: ProducerTemplate = camel.template
}
