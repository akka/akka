/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._
import org.apache.camel.{ ProducerTemplate }
import org.apache.camel.impl.DefaultCamelContext

/**
 * Subclass this abstract class to create an untyped producer actor. This class is meant to be used from Java.
 *
 * @deprecated Akka Camel is deprecated since 2.5.0 in favour of 'Alpakka', the Akka Streams based collection of integrations to various endpoints (including Camel).
 */
@Deprecated
abstract class UntypedProducerActor extends UntypedActor with ProducerSupport {
  /**
   * Called before the message is sent to the endpoint specified by <code>getEndpointUri</code>. The original
   * message is passed as argument. By default, this method simply returns the argument but may be overridden
   * by subclasses.
   */
  def onTransformOutgoingMessage(message: AnyRef): AnyRef = message

  /**
   * Called before the response message is sent to original sender. The original
   * message is passed as argument. By default, this method simply returns the argument but may be overridden
   * by subclasses.
   */
  def onTransformResponse(message: AnyRef): AnyRef = message

  /**
   * Called after a response was received from the endpoint specified by <code>endpointUri</code>. The
   * response is passed as argument. By default, this method sends the response back to the original sender
   * if <code>oneway</code> is <code>false</code>. If <code>oneway</code> is <code>true</code>, nothing is
   * done. This method may be overridden by subclasses (e.g. to forward responses to another actor).
   */
  def onRouteResponse(message: AnyRef): Unit = super.routeResponse(message)

  final override def transformOutgoingMessage(msg: Any): AnyRef = onTransformOutgoingMessage(msg.asInstanceOf[AnyRef])
  final override def transformResponse(msg: Any): AnyRef = onTransformResponse(msg.asInstanceOf[AnyRef])
  final override def routeResponse(msg: Any): Unit = onRouteResponse(msg.asInstanceOf[AnyRef])

  final override def endpointUri: String = getEndpointUri

  final override def oneway: Boolean = isOneway

  /**
   * Default implementation of UntypedActor.onReceive
   */
  final def onReceive(message: Any): Unit = produce(message)

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def getEndpointUri(): String

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer communicates with the Camel endpoint with an in-only message
   * exchange pattern (fire and forget).
   */
  def isOneway(): Boolean = super.oneway

  /**
   * Returns the <code>CamelContext</code>.
   */
  def getCamelContext(): DefaultCamelContext = camel.context

  /**
   * Returns the <code>ProducerTemplate</code>.
   */
  def getProducerTemplate(): ProducerTemplate = camel.template

  /**
   * ''Java API'': Returns the [[akka.camel.Activation]] interface
   * that can be used to wait on activation or de-activation of Camel endpoints.
   * @return the Activation interface
   */
  def getActivation(): Activation = camel
}
