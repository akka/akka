/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.actor.ActorRef
import akka.camel.internal.component.CamelPath
import org.apache.camel.model.ProcessorDefinition
import scala.concurrent.util.Duration

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
class ActorRouteDefinition[T <: ProcessorDefinition[T]](definition: ProcessorDefinition[T]) {
  /**
   * Sends the message to an ActorRef endpoint.
   * @param actorRef the actorRef to the actor.
   * @return the path to the actor, as a camel uri String
   */
  def to(actorRef: ActorRef): T = definition.to(CamelPath.toUri(actorRef))

  /**
   * Sends the message to an ActorRef endpoint
   * @param actorRef the consumer
   * @param autoAck Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   * @param replyTimeout When endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails. It defaults to 1 minute.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   * @return the path to the actor, as a camel uri String
   */
  def to(actorRef: ActorRef, autoAck: Boolean, replyTimeout: Duration): T = definition.to(CamelPath.toUri(actorRef, autoAck, replyTimeout))
}

