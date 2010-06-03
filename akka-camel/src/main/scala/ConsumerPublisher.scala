/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.camel

import collection.mutable.ListBuffer

import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch

import org.apache.camel.builder.RouteBuilder

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.annotation.consume
import se.scalablesolutions.akka.camel.component.ActiveObjectComponent
import se.scalablesolutions.akka.util.Logging

/**
 * Actor that publishes consumer actors as Camel endpoints at the CamelContext managed
 * by se.scalablesolutions.akka.camel.CamelContextManager. It accepts messages of type
 * se.scalablesolutions.akka.camel.service.ConsumerRegistered and
 * se.scalablesolutions.akka.camel.service.ConsumerUnregistered.
 *
 * @author Martin Krasser
 */
class ConsumerPublisher extends Actor with Logging {

  @volatile private var latch = new CountDownLatch(0)

  /**
   *  Adds a route to the actor identified by a Publish message to the global CamelContext.
   */
  protected def receive = {
    case r: ConsumerRegistered => {
      handleConsumerRegistered(r)
      latch.countDown // needed for testing only.
    }
    case u: ConsumerUnregistered => {
      handleConsumerUnregistered(u)
      latch.countDown // needed for testing only.
    }
    case d: ConsumerMethodRegistered => {
      handleConsumerMethodRegistered(d)
      latch.countDown // needed for testing only.
    }
    case SetExpectedMessageCount(num) => {
      // needed for testing only.
      latch = new CountDownLatch(num)
      self.reply(latch)
    }
    case _ => { /* ignore */}
  }

  /**
   * Creates a route to the registered consumer actor.
   */
  private def handleConsumerRegistered(event: ConsumerRegistered) {
    CamelContextManager.context.addRoutes(new ConsumerActorRoute(event.uri, event.id, event.uuid))
    log.info("published actor %s at endpoint %s" format (event.actorRef, event.uri))
  }

  /**
   * Stops route to the already un-registered consumer actor.
   */
  private def handleConsumerUnregistered(event: ConsumerUnregistered) {
    CamelContextManager.context.stopRoute(event.id)
    log.info("unpublished actor %s from endpoint %s" format (event.actorRef, event.uri))
  }

  private def handleConsumerMethodRegistered(event: ConsumerMethodRegistered) {
    val targetMethod = event.method.getName
    val objectId = "%s_%s" format (event.init.actorRef.uuid, targetMethod)

    CamelContextManager.activeObjectRegistry.put(objectId, event.activeObject)
    CamelContextManager.context.addRoutes(new ConsumerMethodRoute(event.uri, objectId, targetMethod))
    log.info("published method %s of %s at endpoint %s" format (targetMethod, event.activeObject, event.uri))
  }
}

private[camel] case class SetExpectedMessageCount(num: Int)

abstract class ConsumerRoute(endpointUri: String, id: String) extends RouteBuilder {
  // TODO: make conversions configurable
  private val bodyConversions = Map(
    "file" -> classOf[InputStream]
  )

  def configure = {
    val schema = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    bodyConversions.get(schema) match {
      case Some(clazz) => from(endpointUri).routeId(id).convertBodyTo(clazz).to(targetUri)
      case None        => from(endpointUri).routeId(id).to(targetUri)
    }
  }

  protected def targetUri: String
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
class ConsumerActorRoute(endpointUri: String, id: String, uuid: Boolean) extends ConsumerRoute(endpointUri, id) {
  protected override def targetUri = (if (uuid) "actor:uuid:%s" else "actor:id:%s") format id
}

class ConsumerMethodRoute(val endpointUri: String, id: String, method: String) extends ConsumerRoute(endpointUri, id) {
  protected override def targetUri = "%s:%s?method=%s" format (ActiveObjectComponent.DefaultSchema, id, method)
}

/**
 * A registration listener that triggers publication and un-publication of consumer actors.
 *
 * @author Martin Krasser
 */
class PublishRequestor extends Actor {
  private val events = ListBuffer[ConsumerEvent]()
  private var publisher: Option[ActorRef] = None

  protected def receive = {
    case ActorRegistered(actor) =>
      for (event <- ConsumerRegistered.forConsumer(actor)) deliverCurrentEvent(event)
    case ActorUnregistered(actor) => 
      for (event <- ConsumerUnregistered.forConsumer(actor)) deliverCurrentEvent(event)
    case AspectInitRegistered(proxy, init) =>
      for (event <- ConsumerMethodRegistered.forConsumer(proxy, init)) deliverCurrentEvent(event)
    case PublishRequestorInit(pub) => {
      publisher = Some(pub)
      deliverBufferedEvents
    }
    case _ => { /* ignore */ }
  }

  private def deliverCurrentEvent(event: ConsumerEvent) = {
    publisher match {
      case Some(pub) => pub ! event
      case None      => events += event
    }
  }

  private def deliverBufferedEvents = {
    for (event <- events) deliverCurrentEvent(event)
    events.clear
  }
}

case class PublishRequestorInit(consumerPublisher: ActorRef)

/**
 * A consumer event.
 *
 * @author Martin Krasser
 */
sealed trait ConsumerEvent

/**
 * Event indicating that a consumer actor has been registered at the actor registry.
 *
 * @param actorRef actor reference
 * @param uri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> is the actor's uuid, <code>false</code> if
 *             <code>id</code> is the actor's id.
 *
 * @author Martin Krasser
 */
case class ConsumerRegistered(actorRef: ActorRef, uri: String, id: String, uuid: Boolean) extends ConsumerEvent

/**
 * Event indicating that a consumer actor has been unregistered from the actor registry.
 *
 * @param actorRef actor reference
 * @param uri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> is the actor's uuid, <code>false</code> if
 *             <code>id</code> is the actor's id.
 *
 * @author Martin Krasser
 */
case class ConsumerUnregistered(actorRef: ActorRef, uri: String, id: String, uuid: Boolean) extends ConsumerEvent

case class ConsumerMethodRegistered(activeObject: AnyRef, init: AspectInit, uri: String, method: Method) extends ConsumerEvent

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerRegistered {
  /**
   * Optionally creates an ConsumerRegistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def forConsumer(actorRef: ActorRef): Option[ConsumerRegistered] = actorRef match {
    case ConsumerDescriptor(ref, uri, id, uuid) => Some(ConsumerRegistered(ref, uri, id, uuid))
    case _                                      => None
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerUnregistered {
  /**
   * Optionally creates an ConsumerUnregistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def forConsumer(actorRef: ActorRef): Option[ConsumerUnregistered] = actorRef match {
    case ConsumerDescriptor(ref, uri, id, uuid) => Some(ConsumerUnregistered(ref, uri, id, uuid))
    case _                                      => None
  }
}

private[camel] object ConsumerMethodRegistered {
  def forConsumer(activeObject: AnyRef, init: AspectInit): List[ConsumerMethodRegistered] = {
    // TODO: support/test annotations on interface methods
    // TODO: support/test annotations on superclass methods
    // TODO: document that overloaded methods are not supported
    if (init.remoteAddress.isDefined) Nil // let remote node publish active object methods on endpoints
    else for (m <- activeObject.getClass.getMethods.toList; if (m.isAnnotationPresent(classOf[consume])))
    yield ConsumerMethodRegistered(activeObject, init, m.getAnnotation(classOf[consume]).value, m)
  }
}

/**
 * Describes a consumer actor with elements that are relevant for publishing an actor at a
 * Camel endpoint (or unpublishing an actor from an endpoint).
 *
 * @author Martin Krasser
 */
private[camel] object ConsumerDescriptor {

  /**
   * An extractor that optionally creates a 4-tuple from a consumer actor reference containing
   * the actor reference itself, endpoint URI, identifier and a hint whether the identifier
   * is the actor uuid or actor id. If <code>actorRef</code> doesn't reference a consumer actor,
   * None is returned.
   */
  def unapply(actorRef: ActorRef): Option[(ActorRef, String, String, Boolean)] =
    unapplyConsumerInstance(actorRef) orElse unapplyConsumeAnnotated(actorRef)

  private def unapplyConsumeAnnotated(actorRef: ActorRef): Option[(ActorRef, String, String, Boolean)] = {
    val annotation = actorRef.actorClass.getAnnotation(classOf[consume])
    if (annotation eq null) None
    else if (actorRef.remoteAddress.isDefined) None
    else Some((actorRef, annotation.value, actorRef.id, false))
  }

  private def unapplyConsumerInstance(actorRef: ActorRef): Option[(ActorRef, String, String, Boolean)] =
    if (!actorRef.actor.isInstanceOf[Consumer]) None
    else if (actorRef.remoteAddress.isDefined) None
    else Some((actorRef, actorRef.actor.asInstanceOf[Consumer].endpointUri, actorRef.uuid, true))
}
