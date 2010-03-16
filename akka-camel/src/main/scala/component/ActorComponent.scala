/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component

import java.lang.{RuntimeException, String}
import java.util.{Map => JavaMap}
import java.util.concurrent.TimeoutException

import org.apache.camel.{Exchange, Consumer, Processor}
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import se.scalablesolutions.akka.actor.{ActorRegistry, Actor}
import se.scalablesolutions.akka.camel.{Failure, CamelMessageConversion, Message}

/**
 * Camel component for sending messages to and receiving replies from actors.
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
 * Camel endpoint for referencing an actor. The actor reference is given by the endpoint URI.
 * An actor can be referenced by its <code>Actor.getId</code> or its <code>Actor.uuid</code>.
 * Supported endpoint URI formats are
 * <code>actor:&lt;actorid&gt;</code>,
 * <code>actor:id:&lt;actorid&gt;</code> and
 * <code>actor:uuid:&lt;actoruuid&gt;</code>.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorProducer

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String, 
                    comp: ActorComponent, 
                    val id: Option[String], 
                    val uuid: Option[String]) extends DefaultEndpoint(uri, comp) {

  /**
   * @throws UnsupportedOperationException 
   */
  def createConsumer(processor: Processor): Consumer =
    throw new UnsupportedOperationException("actor consumer not supported yet")

  /**
   * Creates a new ActorProducer instance initialized with this endpoint.
   */
  def createProducer: ActorProducer = new ActorProducer(this)

  /**
   * Returns true.
   */
  def isSingleton: Boolean = true
}

/**
 * Sends the in-message of an exchange to an actor. If the exchange pattern is out-capable,
 * the producer waits for a reply (using the !! operator), otherwise the ! operator is used
 * for sending the message.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorEndpoint
 *
 * @author Martin Krasser
 */
class ActorProducer(val ep: ActorEndpoint) extends DefaultProducer(ep) {
  import CamelMessageConversion.toExchangeAdapter

  implicit val sender = None

  /**
   * Depending on the exchange pattern, this method either calls processInOut or
   * processInOnly for interacting with an actor. This methods looks up the actor
   * from the ActorRegistry according to this producer's endpoint URI.
   *
   * @param exchange represents the message exchange with the actor.
   */
  def process(exchange: Exchange) {
    val actor = target getOrElse (throw new ActorNotRegisteredException(ep.getEndpointUri))
    if (exchange.getPattern.isOutCapable) processInOut(exchange, actor)
    else processInOnly(exchange, actor)
  }

  /**
   * Send the exchange in-message to the given actor using the ! operator. The message
   * send to the actor is of type se.scalablesolutions.akka.camel.Message.
   */
  protected def processInOnly(exchange: Exchange, actor: Actor): Unit = 
    actor ! exchange.toRequestMessage(Map(Message.MessageExchangeId -> exchange.getExchangeId))

  /**
   * Send the exchange in-message to the given actor using the !! operator. The exchange
   * out-message is populated from the actor's reply message.  The message sent to the
   * actor is of type se.scalablesolutions.akka.camel.Message.
   */
  protected def processInOut(exchange: Exchange, actor: Actor) {
    val header = Map(Message.MessageExchangeId -> exchange.getExchangeId)
    val result: Any = actor !! exchange.toRequestMessage(header)

    result match {
      case Some(msg: Failure) => exchange.fromFailureMessage(msg)
      case Some(msg)          => exchange.fromResponseMessage(Message.canonicalize(msg))
      case None               => {
        throw new TimeoutException("timeout (%d ms) while waiting response from %s"
            format (actor.timeout, ep.getEndpointUri))
      }
    }
  }

  private def target: Option[Actor] =
    if (ep.id.isDefined) targetById(ep.id.get)
    else targetByUuid(ep.uuid.get)

  private def targetById(id: String) = ActorRegistry.actorsFor(id) match {
    case Nil          => None
    case actor :: Nil => Some(actor)
    case actors       => Some(actors.first)
  }

  private def targetByUuid(uuid: String) = ActorRegistry.actorFor(uuid)
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