/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.component.DurationTypeConverter
import org.apache.camel.model.{ RouteDefinition, ProcessorDefinition }

import akka.actor._
import akka.util.Duration
import akka.util.duration._

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor with ConsumerConfig {

  def endpointUri: String

  CamelExtension(context.system).registerConsumer(endpointUri, this, activationTimeout)
}

trait ConsumerConfig {
  //TODO: Explain the parameters better with some examples!

  /**
   * How long should the actor wait for activation before it fails.
   */
  def activationTimeout: Duration = 10 seconds

  /**
   * When endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails. It defaults to 1 minute.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   * When the blocking is set to Blocking replyTimeout is ignored.
   */
  def replyTimeout: Duration = 1 minute

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   */
  def autoack: Boolean = true

  /**
   * The route definition handler for creating a custom route to this consumer instance.
   */
  //TODO: write a test confirming onRouteDefinition gets called
  def onRouteDefinition(rd: RouteDefinition): ProcessorDefinition[_] = rd

  private[camel] def toCamelParameters: String = "autoack=%s&replyTimeout=%s" format (autoack, DurationTypeConverter.toString(replyTimeout))
}

trait ManualAckConsumer extends Consumer{
  override def autoack = false
}

trait ErrorPassing { self : Actor =>
  final override def preRestart(reason: Throwable, message: Option[Any]) {
    sender ! Failure(reason)
  }
}