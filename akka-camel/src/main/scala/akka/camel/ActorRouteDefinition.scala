/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.component.ActorEndpointPath
import akka.actor.ActorRef
import org.apache.camel.model.ProcessorDefinition

/**
 * Wraps a [[org.apache.camel.model.ProcessorDefinition]].
 * There is an implicit conversion in the [[akka.camel]] package object that converts a `ProcessorDefinition` into `this` type.
 * Because of this conversion, it is possible to use an [[akka.actor.ActorRef]] as a `to` parameter in building a route:
 * {{{
 *   class TestRoute(system: ActorSystem) extends RouteBuilder {
 *     val responder = system.actorOf(Props[TestResponder], name = "TestResponder")
 *
 *     def configure {
 *       from("direct:producer").to(responder)
 *     }
 *   }
 * }}}
 * @param definition the processor definition
 */
class ActorRouteDefinition(definition: ProcessorDefinition[_]) {
  /**
   * Sends the message to an ActorRef endpoint.
   * @param actorRef the consumer with a default configuration.
   * @return the path to the actor, as a camel uri String
   */
  def to(actorRef: ActorRef) = definition.to(ActorEndpointPath(actorRef).toCamelPath())

  /**
   * Sends the message to an ActorRef endpoint
   * @param actorRef the consumer
   * @param consumerConfig the configuration for the consumer
   * @return the path to the actor, as a camel uri String
   */
  def to(actorRef: ActorRef, consumerConfig: ConsumerConfig) = definition.to(ActorEndpointPath(actorRef).toCamelPath(consumerConfig))
}

