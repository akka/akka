/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps
import internal.component.DurationTypeConverter
import org.apache.camel.model.{ RouteDefinition, ProcessorDefinition }
import akka.actor._
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import scala.concurrent.util.FiniteDuration

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor with CamelSupport with ConsumerConfig {
  /**
   * Must return the Camel endpoint URI that the consumer wants to consume messages from.
   */
  def endpointUri: String

  /**
   * Registers the consumer endpoint. Note: when overriding this method, be sure to
   * call 'super.preRestart', otherwise the consumer endpoint will not be registered.
   */
  override def preStart() {
    super.preStart()
    // Possible FIXME. registering the endpoint here because of problems
    // with order of execution of trait body in the Java version (UntypedConsumerActor)
    // where getEndpointUri is called before its constructor (where a uri is set to return from getEndpointUri) 
    // and remains null. CustomRouteTest provides a test to verify this.
    camel.registerConsumer(endpointUri, this, activationTimeout)
  }
}

trait ConsumerConfig { this: CamelSupport ⇒
  /**
   * How long the actor should wait for activation before it fails.
   */
  def activationTimeout: FiniteDuration = camel.settings.activationTimeout

  /**
   * When endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails. It defaults to 1 minute.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   */
  def replyTimeout: Duration = camel.settings.replyTimeout

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   */
  def autoAck: Boolean = camel.settings.autoAck

  /**
   * The route definition handler for creating a custom route to this consumer instance.
   */
  def onRouteDefinition(rd: RouteDefinition): ProcessorDefinition[_] = rd

}
