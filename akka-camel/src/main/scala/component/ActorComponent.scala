/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.component

import java.lang.{RuntimeException, String}
import java.util.{Map => JavaMap}

import org.apache.camel.{Exchange, Consumer, Processor}
import org.apache.camel.impl.{DefaultProducer, DefaultEndpoint, DefaultComponent}

import se.scalablesolutions.akka.actor.{ActorRegistry, Actor}

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
  
  private def idAndUuidPair(remaining: String): Tuple2[String, Option[String]] = {
    remaining split "/" toList match {
      case id :: Nil         => (id, None)
      case id :: uuid :: Nil => (id, Some(uuid))
      case _ => throw new IllegalArgumentException(
        "invalid path format: %s - should be <actorid>[/<actoruuid>]" format remaining)
    }
  }

}

/**
 * Camel endpoint for interacting with actors. An actor can be addressed by its
 * <code>Actor.id</code> or by an <code>Actor.id</code> - <code>Actor.uuid</code>
 * combination. The URI format is <code>actor://<actorid>[/<actoruuid>]</code>.
 *
 * @see se.scalablesolutions.akka.camel.component.ActorComponent
 * @see se.scalablesolutions.akka.camel.component.ActorProducer

 * @author Martin Krasser
 */
class ActorEndpoint(uri: String, comp: ActorComponent, val id: String, val uuid: Option[String]) extends DefaultEndpoint(uri, comp) {

  // TODO: clarify uuid details
  // - do they change after persist/restore
  // - what about remote actors and uuids

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

  implicit val sender = Some(Sender)

  def process(exchange: Exchange) {
    val actor = target getOrElse (throw new ActorNotRegisteredException(ep.id, ep.uuid))
    if (exchange.getPattern.isOutCapable)
      processInOut(exchange, actor)
    else
      processInOnly(exchange, actor)
  }

  override def start {
    super.start
  }

  protected def receive = {
    throw new UnsupportedOperationException
  }

  protected def processInOnly(exchange: Exchange, actor: Actor) {
    actor ! exchange.getIn
  }

  protected def processInOut(exchange: Exchange, actor: Actor) {
    val outmsg = exchange.getOut
    // TODO: make timeout configurable
    // TODO: send immutable message
    // TODO: support asynchronous communication
    //       - jetty component: jetty continuations
    //       - file component: completion callbacks
    val result: Any = actor !! exchange.getIn

    result match {
      case Some((body, headers:Map[String, Any])) => {
        outmsg.setBody(body)
        for (header <- headers)
          outmsg.getHeaders.put(header._1, header._2.asInstanceOf[AnyRef])
      }
      case Some(body) => outmsg.setBody(body)
    }
  }

  private def target: Option[Actor] = {
    ActorRegistry.actorsFor(ep.id) match {
      case actor :: Nil if targetMatchesUuid(actor) => Some(actor)
      case Nil    => None
      case actors => actors find (targetMatchesUuid _)
    }
  }

  private def targetMatchesUuid(target: Actor): Boolean =
    // if ep.uuid is not defined always return true
    target.uuid == (ep.uuid getOrElse target.uuid)

}

/**
 * Generic message sender used by ActorProducer.
 *
 * @author Martin Krasser
 */
private[component] object Sender extends Actor {

  start

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
class ActorNotRegisteredException(name: String, uuid: Option[String]) extends RuntimeException {

  override def getMessage = "actor(id=%s,uuid=%s) not registered" format (name, uuid getOrElse "<none>")

}