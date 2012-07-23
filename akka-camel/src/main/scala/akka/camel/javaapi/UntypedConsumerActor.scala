/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._
import org.apache.camel.{ ProducerTemplate, CamelContext }

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 */
abstract class UntypedConsumerActor extends UntypedActor with Consumer {
  final def endpointUri: String = getEndpointUri

  /**
   * ''Java API'': Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

  /**
   * ''Java API'': Returns the [[org.apache.camel.CamelContext]]
   * @return the CamelContext
   */
  protected def getCamelContext(): CamelContext = camelContext

  /**
   * ''Java API'': Returns the [[org.apache.camel.ProducerTemplate]]
   * @return the ProducerTemplate
   */
  protected def getProducerTemplate(): ProducerTemplate = camel.template

  /**
   * ''Java API'': Returns the [[akka.camel.Activation]] interface
   * that can be used to wait on activation or de-activation of Camel endpoints.
   * @return the Activation interface
   */
  protected def getActivation(): Activation = camel
}
