/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.camel.internal

import akka.actor._
import akka.camel._
import akka.camel.internal.component.CamelPath
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.RouteDefinition

import scala.language.existentials

/**
 * INTERNAL API
 * Builder of a route to a target which can be an actor.
 *
 * @param endpointUri endpoint URI of the consumer actor.
 *
 *
 */
private[camel] class ConsumerActorRouteBuilder(endpointUri: String, consumer: ActorRef, config: ConsumerConfig, settings: CamelSettings) extends RouteBuilder {

  protected def targetActorUri = CamelPath.toUri(consumer, config.autoAck, config.replyTimeout)

  def configure(): Unit =
    applyUserRouteCustomization(
      settings.Conversions.apply(
        endpointUri take endpointUri.indexOf(":"), // e.g. "http" from "http://whatever/..."
        from(endpointUri).routeId(consumer.path.toString))).to(targetActorUri)

  def applyUserRouteCustomization(rd: RouteDefinition) = config.onRouteDefinition(rd)
}
