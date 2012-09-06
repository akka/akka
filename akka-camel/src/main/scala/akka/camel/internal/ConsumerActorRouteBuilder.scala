/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal

import akka.camel._
import component.CamelPath
import java.io.InputStream

import org.apache.camel.builder.RouteBuilder

import akka.actor._
import org.apache.camel.model.RouteDefinition

/**
 * For internal use only.
 * Builder of a route to a target which can be an actor.
 *
 * @param endpointUri endpoint URI of the consumer actor.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerActorRouteBuilder(endpointUri: String, consumer: ActorRef, config: ConsumerConfig) extends RouteBuilder {

  protected def targetActorUri = CamelPath.toUri(consumer, config.autoAck, config.replyTimeout)

  def configure() {
    val scheme = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."

    val route = from(endpointUri).routeId(consumer.path.toString)
    val converted = Conversions(scheme, route)
    val userCustomized = applyUserRouteCustomization(converted)
    userCustomized.to(targetActorUri)
  }

  def applyUserRouteCustomization(rd: RouteDefinition) = config.onRouteDefinition(rd)

  object Conversions {
    private val bodyConversions = Map(
      "file" -> classOf[InputStream])

    def apply(scheme: String, routeDefinition: RouteDefinition): RouteDefinition = bodyConversions.get(scheme) match {
      case Some(clazz) ⇒ routeDefinition.convertBodyTo(clazz)
      case None        ⇒ routeDefinition
    }
  }

}
