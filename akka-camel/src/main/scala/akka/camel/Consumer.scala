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
trait Consumer extends Actor with ConsumerConfig{

  def endpointUri : String

  CamelExtension(context.system).registerConsumer(endpointUri, this, activationTimeout)
}


trait ConsumerConfig{
  //TODO: Explain the parameters better with some examples!

  /**
   * How long should the actor wait for activation before it fails.
   */
  def activationTimeout: Duration = 10 seconds

  /**
   * When endpoint is outCapable (can produce responses) outTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails. It defaults to Int.MaxValue seconds.
   * It can be also overwritten by setting @see blocking property
   */
  def outTimeout : Duration = Int.MaxValue seconds

  /**
   * Determines whether two-way communications between an endpoint and this consumer actor
   * should be done in blocking or non-blocking mode (default is non-blocking). This method
   * doesn't have any effect on one-way communications (they'll never block).
   */
  def blocking : BlockingOrNot = NonBlocking

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   */
  def autoack : Boolean = true

  /**
   * The route definition handler for creating a custom route to this consumer instance.
   */
  //TODO: write a test confirming onRouteDefinition gets called
  def onRouteDefinition(rd: RouteDefinition) : ProcessorDefinition[_] = rd
}

sealed trait BlockingOrNot
case object NonBlocking extends BlockingOrNot
case class Blocking(timeout : Duration) extends BlockingOrNot

object Blocking{
  def seconds(timeout:Long) = Blocking(timeout seconds)
  def millis(timeout:Long) = Blocking(timeout millis)
}


class ConsumerRequiresFromEndpointException extends RuntimeException("Consumer needs to provide from endpoint. Please make sure the consumer calls method from(\"some uri\") in the body of constructor.")