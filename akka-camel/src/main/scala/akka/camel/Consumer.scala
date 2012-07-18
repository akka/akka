/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.component.DurationTypeConverter
import org.apache.camel.model.{ RouteDefinition, ProcessorDefinition }
import akka.actor._
import akka.util.Duration
import akka.util.duration._
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Mixed in by Actor implementations that consume message from Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Consumer extends Actor with CamelSupport with ConsumerConfig {

  def endpointUri: String

  camel.registerConsumer(endpointUri, this, activationTimeout)
}

case object DefaultConsumerParameters {
  val replyTimeout = 1 minute
  val autoAck = true
}

trait ConsumerConfig { this: Actor ⇒
  private val config = this.context.system.settings.config
  /**
   * How long the actor should wait for activation before it fails.
   */
  def activationTimeout: Duration = Duration(config.getMilliseconds("akka.camel.consumer.activationTimeout"), MILLISECONDS)

  /**
   * When endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails. It defaults to 1 minute.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   */
  def replyTimeout: Duration = Duration(config.getMilliseconds("akka.camel.consumer.replyTimeout"), MILLISECONDS)

  /**
   * Determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   */
  def autoAck: Boolean = config.getBoolean("akka.camel.consumer.autoAck")

  /**
   * The route definition handler for creating a custom route to this consumer instance.
   */
  //FIXME: write a test confirming onRouteDefinition gets called
  def onRouteDefinition(rd: RouteDefinition): ProcessorDefinition[_] = rd

}
