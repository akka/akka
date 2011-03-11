/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel

import org.apache.camel.model.{RouteDefinition, ProcessorDefinition}

import akka.actor._

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer { this: Actor =>
  import RouteDefinitionHandler._

  /**
   * The default route definition handler is the identity function
   */
  private[camel] var routeDefinitionHandler: RouteDefinitionHandler = identity

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def endpointUri: String

  /**
   * Determines whether two-way communications between an endpoint and this consumer actor
   * should be done in blocking or non-blocking mode (default is non-blocking). This method
   * doesn't have any effect on one-way communications (they'll never block).
   */
  def blocking = false

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   */
  def autoack = true

  /**
   * Sets the route definition handler for creating a custom route to this consumer instance.
   */
  def onRouteDefinition(h: RouteDefinition => ProcessorDefinition[_]): Unit = onRouteDefinition(from(h))

  /**
   * Sets the route definition handler for creating a custom route to this consumer instance.
   * <p>
   * Java API.
   */
  def onRouteDefinition(h: RouteDefinitionHandler): Unit = routeDefinitionHandler = h
}

/**
 *  Java-friendly Consumer.
 *
 * @see UntypedConsumerActor
 * @see RemoteUntypedConsumerActor
 *
 * @author Martin Krasser
 */
trait UntypedConsumer extends Consumer { self: UntypedActor =>
  final override def endpointUri = getEndpointUri
  final override def blocking = isBlocking
  final override def autoack = isAutoack

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

  /**
   * Determines whether two-way communications between an endpoint and this consumer actor
   * should be done in blocking or non-blocking mode (default is non-blocking). This method
   * doesn't have any effect on one-way communications (they'll never block).
   */
  def isBlocking() = super.blocking

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   */
  def isAutoack() = super.autoack
}

/**
 * Subclass this abstract class to create an MDB-style untyped consumer actor. This
 * class is meant to be used from Java.
 */
abstract class UntypedConsumerActor extends UntypedActor with UntypedConsumer

/**
 * A callback handler for route definitions to consumer actors.
 *
 * @author Martin Krasser
 */
trait RouteDefinitionHandler {
  def onRouteDefinition(rd: RouteDefinition): ProcessorDefinition[_]
}

/**
 * The identity route definition handler.
 *
 * @author Martin Krasser
 *
 */
class RouteDefinitionIdentity extends RouteDefinitionHandler {
  def onRouteDefinition(rd: RouteDefinition) = rd
}

/**
 * @author Martin Krasser
 */
object RouteDefinitionHandler {
  /**
   * Returns the identity route definition handler
   */
  val identity = new RouteDefinitionIdentity

  /**
   * Created a route definition handler from the given function.
   */
  def from(f: RouteDefinition => ProcessorDefinition[_]) = new RouteDefinitionHandler {
    def onRouteDefinition(rd: RouteDefinition) = f(rd)
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object Consumer {
  /**
   * Applies a function <code>f</code> to <code>actorRef</code> if <code>actorRef</code>
   * references a consumer actor. A valid reference to a consumer actor is a local actor
   * reference with a target actor that implements the <code>Consumer</code> trait. The
   * target <code>Consumer</code> object is passed as argument to <code>f</code>. This
   * method returns <code>None</code> if <code>actorRef</code> is not a valid reference
   * to a consumer actor, <code>Some</code> consumer actor otherwise.
   */
  def withConsumer[T](actorRef: ActorRef)(f: Consumer => T): Option[T] = {
    if (!actorRef.actor.isInstanceOf[Consumer]) None
    else if (actorRef.homeAddress.isDefined) None
    else Some(f(actorRef.actor.asInstanceOf[Consumer]))
  }
}
