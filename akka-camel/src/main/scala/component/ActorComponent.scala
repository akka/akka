/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component

import java.lang.{RuntimeException, String}
import java.util.{Map => JavaMap}

import org.apache.camel.{Exchange, Consumer, Processor}
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import se.scalablesolutions.akka.actor.{ActorRegistry, Actor}
import se.scalablesolutions.akka.camel.{CamelMessageWrapper, Message}

/**
 * Camel component for interacting with actors.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 * @see se.scalablesolutions.akka.camel.component.ActorProducer
 *
 * @author Martin Krasser
 */
class ActorComponent extends DefaultComponent {

  def createEndpoint(uri: String, remaining: String, parameters: JavaMap[String, Object]): ActorEndpoint = {
    val idAndUuid = idAndUuidPair(remaining)
    new ActorEndpoint(uri, this, idAndUuid._1, idAndUuid._2)
  }
  
  private def idAndUuidPair(remaining: String): Tuple2[Option[String], Option[String]] = {
    remaining split ":" toList match {
      case             id :: Nil => (Some(id), None)
      case   "id" ::   id :: Nil => (Some(id), None)
      case "uuid" :: uuid :: Nil => (None, Some(uuid))
      case _ => throw new IllegalArgumentException(
        "invalid path format: %s - should be <actorid> or id:<actorid> or uuid:<actoruuid>" format remaining)
    }
  }

}

/**
 * Camel endpoint for interacting with actors. An actor can be addressed by its
 * <code>Actor.getId</code> or its <code>Actor.uuid</code> combination. Supported URI formats are
 * <code>actor:&lt;actorid&gt;</code>,
 * <code>actor:id:&lt;actorid&gt;</code> and
 * <code>actor:uuid:&lt;actoruuid&gt;</code>.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorProducer

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String, comp: ActorComponent, val id: Option[String], val uuid: Option[String]) extends DefaultEndpoint(uri, comp) {

  /**
   * @throws UnsupportedOperationException 
   */
  def createConsumer(processor: Processor): Consumer =
    throw new UnsupportedOperationException("actor consumer not supported yet")

  def createProducer: ActorProducer = new ActorProducer(this)

  def isSingleton: Boolean = true
  
}

/**
 * Sends the in-message of an exchange to an actor. If the exchange pattern is out-capable,
 * the producer waits for a reply (using the !! operator), otherwise the ! operator is used
 * for sending the message. Asynchronous communication is not implemented yet but will be
 * added for Camel components that support the Camel Async API (like the jetty component that
 * makes use of Jetty continuations).
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 *
 * @author Martin Krasser
 */
class ActorProducer(val ep: ActorEndpoint) extends DefaultProducer(ep) {

  implicit val sender = Some(new Sender)

  def process(exchange: Exchange) {
    val actor = target getOrElse (throw new ActorNotRegisteredException(ep.getEndpointUri))
    if (exchange.getPattern.isOutCapable)
      processInOut(exchange, actor)
    else
      processInOnly(exchange, actor)
  }

  override def start {
    super.start
    sender.get.start
  }

  override def stop {
    sender.get.stop
    super.stop
  }

  protected def receive = {
    throw new UnsupportedOperationException
  }

  protected def processInOnly(exchange: Exchange, actor: Actor) {
    actor ! Message(exchange.getIn)
  }

  protected def processInOut(exchange: Exchange, actor: Actor) {

    import CamelMessageWrapper._

    // TODO: make timeout configurable
    // TODO: support asynchronous communication
    //       - jetty component: jetty continuations
    //       - file component: completion callbacks
    val result: Any = actor !! Message(exchange.getIn)

    result match {
      case Some(m:Message) => {
        exchange.getOut.from(m)
      }
      case Some(body) => {
        exchange.getOut.setBody(body)
      }
    }
  }

  private def target: Option[Actor] = {
    if (ep.id.isDefined) targetById(ep.id.get)
    else targetByUuid(ep.uuid.get)
  }

  private def targetById(id: String) = ActorRegistry.actorsFor(id) match {
    case Nil          => None
    case actor :: Nil => Some(actor)
    case actors       => Some(actors.first)
  }

  private def targetByUuid(uuid: String) = ActorRegistry.actorFor(uuid)

}

/**
 * Generic message sender used by ActorProducer.
 *
 * @author Martin Krasser
 */
private[component] class Sender extends Actor {

  /**
   * Ignores any message.
   */
  protected def receive = {
    case _ => { /* ignore any reply */ }
  }

}

/**
 * Thrown to indicate that an actor referenced by an endpoint URI cannot be
 * found in the ActorRegistry.
 *
 * @author Martin Krasser
 */
class ActorNotRegisteredException(uri: String) extends RuntimeException {

  override def getMessage = "%s not registered" format uri

}