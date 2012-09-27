/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.camel.internal.CamelSupervisor.Register
import org.apache.camel.model.{ RouteDefinition, ProcessorDefinition }
import akka.actor._
import scala.concurrent.util.FiniteDuration
import akka.dispatch.Mapper

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor with CamelSupport {
  import Consumer._
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
    register()
  }

  private[this] def register() {
    camel.supervisor ! Register(self, endpointUri, Some(ConsumerConfig(activationTimeout, replyTimeout, autoAck, onRouteDefinition)))
  }

  /**
   * How long the actor should wait for activation before it fails.
   */
  def activationTimeout: FiniteDuration = camel.settings.ActivationTimeout

  /**
   * When endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails. It defaults to 1 minute.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   */
  def replyTimeout: FiniteDuration = camel.settings.ReplyTimeout

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   */
  def autoAck: Boolean = camel.settings.AutoAck

  /**
   * Returns the route definition handler for creating a custom route to this consumer.
   * By default it returns an identity function, override this method to
   * return a custom route definition handler. The returned function is not allowed to close over 'this', meaning it is
   * not allowed to refer to the actor instance itself, since that can easily cause concurrent shared state issues.
   */
  def onRouteDefinition: RouteDefinition ⇒ ProcessorDefinition[_] = {
    val mapper = getRouteDefinitionHandler
    if (mapper != identityRouteMapper) mapper.apply _
    else identityRouteMapper
  }

  /**
   * Java API. Returns the [[akka.dispatch.Mapper]] function that will be used as a route definition handler
   * for creating custom route to this consumer. By default it returns an identity function, override this method to
   * return a custom route definition handler. The [[akka.dispatch.Mapper]] is not allowed to close over 'this', meaning it is
   * not allowed to refer to the actor instance itself, since that can easily cause concurrent shared state issues.
   */
  def getRouteDefinitionHandler: Mapper[RouteDefinition, ProcessorDefinition[_]] = identityRouteMapper
}

/**
 * Internal use only.
 */
private[camel] object Consumer {
  val identityRouteMapper = new Mapper[RouteDefinition, ProcessorDefinition[_]]() {
    override def checkedApply(rd: RouteDefinition): ProcessorDefinition[_] = rd
  }
}
/**
 * For internal use only.
 * Captures the configuration of the Consumer.
 */
private[camel] case class ConsumerConfig(activationTimeout: FiniteDuration, replyTimeout: FiniteDuration, autoAck: Boolean, onRouteDefinition: RouteDefinition ⇒ ProcessorDefinition[_]) extends NoSerializationVerificationNeeded
