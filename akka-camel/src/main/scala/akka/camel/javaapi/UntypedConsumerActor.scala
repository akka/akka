/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._
import org.apache.camel.ProducerTemplate
import org.apache.camel.impl.DefaultCamelContext

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 *
 * @deprecated Akka Camel is deprecated since 2.5.0 in favour of 'Alpakka', the Akka Streams based collection of integrations to various endpoints (including Camel).
 */
@Deprecated
abstract class UntypedConsumerActor extends UntypedActor with Consumer {
  final def endpointUri: String = getEndpointUri

  /**
   * Java API: Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

  /**
   * Java API: Returns the [[org.apache.camel.impl.DefaultCamelContext]]
   * @return the CamelContext
   */
  protected def getCamelContext(): DefaultCamelContext = camelContext

  /**
   * Java API: Returns the [[org.apache.camel.ProducerTemplate]]
   * @return the ProducerTemplate
   */
  protected def getProducerTemplate(): ProducerTemplate = camel.template

  /**
   * Java API: Returns the [[akka.camel.Activation]] interface
   * that can be used to wait on activation or de-activation of Camel endpoints.
   * @return the Activation interface
   */
  protected def getActivation(): Activation = camel
}
