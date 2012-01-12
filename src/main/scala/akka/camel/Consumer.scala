/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.camel

import org.apache.camel.model.{RouteDefinition, ProcessorDefinition}

import akka.actor._
import akka.util.Duration
import akka.util.duration._

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor{

  private[this] val camel : Camel = CamelExtension(context.system)

  def endpointUri : String
  def config = ConsumerConfig() //TODO figure out how not to recreate config every time and keep it overridable

  camel.registerConsumer(endpointUri, this, config.activationTimeout)

}

/** //TODO: Explain the parameters better with some examples!
 * @param activationTimeout How long should the actor wait for activation before it fails.
 *
 * @param outTimeout When endpoint is outCapable (can produce responses) outTimeout is the maximum time
 * the endpoint can take to send the response before the message exchange fails. It defaults to Int.MaxValue seconds.
 * It can be also overwritten by setting @see blocking property
 *
 * @param blocking Determines whether two-way communications between an endpoint and this consumer actor
 * should be done in blocking or non-blocking mode (default is non-blocking). This method
 * doesn't have any effect on one-way communications (they'll never block).
 *
 * @param autoack Determines whether one-way communications between an endpoint and this consumer actor
 * should be auto-acknowledged or application-acknowledged.
 *
  */

case class ConsumerConfig(activationTimeout: Duration = 10 seconds,
                          outTimeout : Duration = Int.MaxValue seconds,
                          blocking : BlockingOrNot = NonBlocking,
                          autoack : Boolean = true
                          ) {
  /**
   * The route definition handler for creating a custom route to this consumer instance.
   */
  //TODO: write a test confirming onRouteDefinition gets called
  def onRouteDefinition(rd: RouteDefinition) : ProcessorDefinition[_] = rd
}

sealed trait BlockingOrNot
case object NonBlocking extends BlockingOrNot
case class Blocking(timeout : Duration) extends BlockingOrNot{
  override def toString = "Blocking(%d nanos)".format(timeout.toNanos)
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
  final def endpointUri = getEndpointUri


  //TODO figure out how not to delegate but use CustomerConfig from Java
  override def config = new ConsumerConfig(blocking = isBlocking(), autoack = isAutoack(), activationTimeout = getActivationTimeout()){
    override def onRouteDefinition(rd: RouteDefinition) = UntypedConsumer.this.onRouteDefinition(rd)
  }

  /**
   * Returns the Camel endpoint URI to consume messages from.
   */
  def getEndpointUri(): String

  /**
   * Determines whether two-way communications between an endpoint and this consumer actor
   * should be done in blocking or non-blocking mode (default is non-blocking). This method
   * doesn't have any effect on one-way communications (they'll never block).
   */
  def isBlocking() : BlockingOrNot = NonBlocking

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   */
  def isAutoack() = false

  def getActivationTimeout(): Duration = 10 seconds

  def onRouteDefinition(rd: RouteDefinition) : ProcessorDefinition[_] = rd

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


class ConsumerRequiresFromEndpointException extends RuntimeException("Consumer needs to provide from endpoint. Please make sure the consumer calls method from(\"some uri\") in the body of constructor.")