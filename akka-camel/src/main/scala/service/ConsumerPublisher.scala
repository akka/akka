/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.camel.service

import java.io.InputStream
import java.util.concurrent.CountDownLatch

import org.apache.camel.builder.RouteBuilder

import se.scalablesolutions.akka.actor.{ActorUnregistered, ActorRegistered, Actor}
import se.scalablesolutions.akka.actor.annotation.consume
import se.scalablesolutions.akka.camel.{Consumer, CamelContextManager}
import se.scalablesolutions.akka.util.Logging

/**
 * Actor that publishes consumer actors as Camel endpoints at the CamelContext managed
 * by se.scalablesolutions.akka.camel.CamelContextManager. It accepts messages of type
 * se.scalablesolutions.akka.camel.service.Publish. 
 *
 * @author Martin Krasser
 */
class ConsumerPublisher extends Actor with Logging {
  @volatile private var latch = new CountDownLatch(0)

  /**
   * Adds a route to the actor identified by a Publish message to the global CamelContext.
   */
  protected def receive = {
    case p: Publish => publish(new ConsumerRoute(p.endpointUri, p.id, p.uuid))
    case _          => { /* ignore */}
  }

  /**
   * Sets the number of expected Publish messages received by this actor. Used for testing
   * only.
   */
  private[camel] def expectPublishCount(count: Int): Unit = latch = new CountDownLatch(count)

  /**
   * Waits for the number of expected Publish messages to arrive. Used for testing only.
   */
  private[camel] def awaitPublish = latch.await

  private def publish(route: ConsumerRoute) {
    CamelContextManager.context.addRoutes(route)
    log.info("published actor via endpoint %s" format route.endpointUri)
    latch.countDown // needed for testing only.
  }
}

/**
 * Defines the route to a consumer actor.
 *
 * @param endpointUri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> refers to Actor.uuid, <code>false</code> if
 *             <code>id</code> refers to Actor.getId.
 *
 * @author Martin Krasser
 */
class ConsumerRoute(val endpointUri: String, id: String, uuid: Boolean) extends RouteBuilder {
  // TODO: make conversions configurable
  private val bodyConversions = Map(
    "file" -> classOf[InputStream]
  )

  def configure = {
    val schema = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    bodyConversions.get(schema) match {
      case Some(clazz) => from(endpointUri).convertBodyTo(clazz).to(actorUri)
      case None        => from(endpointUri).to(actorUri)
    }
  }

  private def actorUri = (if (uuid) "actor:uuid:%s" else "actor:id:%s") format id
}

/**
 * A registration listener that publishes consumer actors (and ignores other actors).
 *
 * @author Martin Krasser
 */
class PublishRequestor(consumerPublisher: Actor) extends Actor {
  protected def receive = {
    case ActorUnregistered(actor) => { /* ignore */ }
    case ActorRegistered(actor)   => Publish.forConsumer(actor) match {
      case Some(publish) => consumerPublisher ! publish
      case None          => { /* ignore */ }
    }
  }
}

/**
 * Request message for publishing a consumer actor.
 *
 * @param endpointUri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> refers to Actor.uuid, <code>false</code> if
 *             <code>id</code> refers to Acotr.getId.
 *
 * @author Martin Krasser
 */
case class Publish(endpointUri: String, id: String, uuid: Boolean)

/**
 * @author Martin Krasser
 */
object Publish {

  /**
   * Creates a list of Publish request messages for all consumer actors in the <code>actors</code>
   * list.
   */
  def forConsumers(actors: List[Actor]): List[Publish] =
    for (actor <- actors; pub = forConsumer(actor); if pub.isDefined) yield pub.get

  /**
   * Creates a Publish request message if <code>actor</code> is a consumer actor.
   */
  def forConsumer(actor: Actor): Option[Publish] =
    forConsumeAnnotated(actor) orElse forConsumerType(actor)

  private def forConsumeAnnotated(actor: Actor): Option[Publish] = {
    val annotation = actor.getClass.getAnnotation(classOf[consume])
    if (annotation eq null) None
    else if (actor._remoteAddress.isDefined) None // do not publish proxies
    else Some(Publish(annotation.value, actor.getId, false))
  }

  private def forConsumerType(actor: Actor): Option[Publish] =
    if (!actor.isInstanceOf[Consumer]) None
    else if (actor._remoteAddress.isDefined) None
    else Some(Publish(actor.asInstanceOf[Consumer].endpointUri, actor.uuid, true))
}
