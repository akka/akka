/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.Register
import org.apache.camel.model.{ RouteDefinition, ProcessorDefinition }
import akka.actor._
import scala.concurrent.util.Duration
import akka.dispatch.Mapper

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor with CamelSupport {
  private val identityRouteMapper = new Mapper[RouteDefinition, ProcessorDefinition[_]]() {
    override def checkedApply(rd: RouteDefinition): ProcessorDefinition[_] = rd
  }

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
    camel.supervisor ! Register(self, endpointUri, Some(ConsumerConfig(activationTimeout, replyTimeout, autoAck, onRouteDefinition)))
  }
  /**
   * How long the actor should wait for activation before it fails.
   */
  def activationTimeout: Duration = camel.settings.activationTimeout

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
   * Returns the route definition handler for creating a custom route to this consumer.
   * By default it returns an identity function, override this method to
   * return a custom route definition handler.
   */
  //FIXME: write a test confirming onRouteDefinition gets called
  def onRouteDefinition: RouteDefinition ⇒ ProcessorDefinition[_] = (rd) ⇒ getRouteDefinitionHandler(rd)

  /**
   * Java API. Returns the Mapper function that will be used as a route definition handler
   * for creating custom route to this consumer. By default it returns an identity function, override this method to
   * return a custom route definition handler.
   */
  def getRouteDefinitionHandler: Mapper[RouteDefinition, ProcessorDefinition[_]] = identityRouteMapper
}

/**
 * For internal use only.
 * Captures the configuration of the Consumer.
 */
private[camel] case class ConsumerConfig(activationTimeout: Duration, replyTimeout: Duration, autoAck: Boolean, onRouteDefinition: RouteDefinition ⇒ ProcessorDefinition[_])
