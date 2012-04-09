/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.javaapi

import akka.actor.UntypedActor
import akka.camel._
import org.apache.camel.{ CamelContext, ProducerTemplate }

/**
 * Subclass this abstract class to create an untyped producer actor. This class is meant to be used from Java.
 *
 * @author Martin Krasser
 */
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

  final override def transformOutgoingMessage(msg: Any): AnyRef = msg match {
    case msg: AnyRef ⇒ onTransformOutgoingMessage(msg)
  }

  final override def transformResponse(msg: Any): AnyRef = msg match {
    case msg: AnyRef ⇒ onTransformResponse(msg)
  }

  final override def routeResponse(msg: Any): Any = msg match {
    case msg: AnyRef ⇒ onRouteResponse(msg)
  }

  final override def endpointUri = getEndpointUri

  final override def oneway = isOneway

  /**
   * Default implementation of UntypedActor.onReceive
   */
  def onReceive(message: Any) {
    produce(message)
  }

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def getEndpointUri(): String

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer communicates with the Camel endpoint with an in-only message
   * exchange pattern (fire and forget).
   */
  def isOneway() = super.oneway

  /**
   * Returns the <code>CamelContext</code>.
   */
  def getCamelContext(): CamelContext = camel.context

  /**
   * Returns the <code>ProducerTemplate</code>.
   */
  def getProducerTemplate(): ProducerTemplate = camel.template
}
