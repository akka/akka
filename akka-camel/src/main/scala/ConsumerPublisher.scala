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
 * @author Martin Krasser
 */
private[camel] object ConsumerPublisher extends Logging {
  /**
   * Creates a route to the registered consumer actor.
   */
  def handleConsumerRegistered(event: ConsumerRegistered) {
    CamelContextManager.context.addRoutes(new ConsumerActorRoute(event.uri, event.id, event.uuid))
    log.info("published actor %s at endpoint %s" format (event.actorRef, event.uri))
  }

  /**
   * Stops route to the already un-registered consumer actor.
   */
  def handleConsumerUnregistered(event: ConsumerUnregistered) {
    CamelContextManager.context.stopRoute(event.id)
    log.info("unpublished actor %s from endpoint %s" format (event.actorRef, event.uri))
  }

  /**
   * Creates a route to an active object method.
   */
  def handleConsumerMethodRegistered(event: ConsumerMethodRegistered) {
    val targetMethod = event.method.getName
    val objectId = "%s_%s" format (event.init.actorRef.uuid, targetMethod)

    CamelContextManager.activeObjectRegistry.put(objectId, event.activeObject)
    CamelContextManager.context.addRoutes(new ConsumerMethodRoute(event.uri, objectId, targetMethod))
    log.info("published method %s of %s at endpoint %s" format (targetMethod, event.activeObject, event.uri))
  }
}

/**
 * Actor that publishes consumer actors and active object methods at Camel endpoints.
 * The Camel context used for publishing is CamelContextManager.context. This actor
 * accepts messages of type
 * se.scalablesolutions.akka.camel.service.ConsumerRegistered,
 * se.scalablesolutions.akka.camel.service.ConsumerMethodRegistered and
 * se.scalablesolutions.akka.camel.service.ConsumerUnregistered.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerPublisher extends Actor {
  import ConsumerPublisher._

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
}

/**
 * Command message used For testing-purposes only.
 */
private[camel] case class SetExpectedMessageCount(num: Int)


/**
 * Defines an abstract route to a target which is either an actor or an active object method..
 *
 * @param endpointUri endpoint URI of the consumer actor or active object method.
 * @param id actor identifier or active object identifier (registry key).
 *
 * @author Martin Krasser
 */
private[camel] abstract class ConsumerRoute(endpointUri: String, id: String) extends RouteBuilder {
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
private[camel] class ConsumerActorRoute(endpointUri: String, id: String, uuid: Boolean) extends ConsumerRoute(endpointUri, id) {
  protected override def targetUri = (if (uuid) "actor:uuid:%s" else "actor:id:%s") format id
}

/**
 * Defines the route to an active object method..
 *
 * @param endpointUri endpoint URI of the consumer actor method
 * @param id active object identifier
 * @param method name of the method to invoke.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerMethodRoute(val endpointUri: String, id: String, method: String) extends ConsumerRoute(endpointUri, id) {
  protected override def targetUri = "%s:%s?method=%s" format (ActiveObjectComponent.InternalSchema, id, method)
}

/**
 * A registration listener that triggers publication of consumer actors and active object
 * methods as well as un-publication of consumer actors. This actor needs to be initialized
 * with a <code>PublishRequestorInit</code> command message for obtaining a reference to
 * a <code>publisher</code> actor. Before initialization it buffers all outbound messages
 * and delivers them to the <code>publisher</code> when receiving a
 * <code>PublishRequestorInit</code> message. After initialization, outbound messages are
 * delivered directly without buffering.
 *
 * @see PublishRequestorInit
 *
 * @author Martin Krasser
 */
private[camel] class PublishRequestor extends Actor {
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

/**
 * Command message to initialize a PublishRequestor to use <code>consumerPublisher</code>
 * for publishing actors or active object methods.
 */
private[camel] case class PublishRequestorInit(consumerPublisher: ActorRef)

/**
 * A consumer (un)registration event.
 *
 * @author Martin Krasser
 */
private[camel] sealed trait ConsumerEvent

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
private[camel] case class ConsumerRegistered(actorRef: ActorRef, uri: String, id: String, uuid: Boolean) extends ConsumerEvent

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
private[camel] case class ConsumerUnregistered(actorRef: ActorRef, uri: String, id: String, uuid: Boolean) extends ConsumerEvent

/**
 * Event indicating that an active object proxy has been created for a POJO. For each
 * <code>@consume</code> annotated POJO method a separate instance of this class is
 * created.
 *
 * @param activeObject active object (proxy).
 * @param init
 * @param uri endpoint URI of the active object method
 * @param method method to be published.
 *
 * @author Martin Krasser
 */
private[camel] case class ConsumerMethodRegistered(activeObject: AnyRef, init: AspectInit, uri: String, method: Method) extends ConsumerEvent

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

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerMethodRegistered {
  /**
   * Creates a list of ConsumerMethodRegistered event messages for an active object or an empty
   * list if the active object is a proxy for an remote active object or the active object doesn't
   * have any <code>@consume</code> annotated methods.
   */
  def forConsumer(activeObject: AnyRef, init: AspectInit): List[ConsumerMethodRegistered] = {
    // TODO: support consumer annotation inheritance
    // - visit overridden methods in superclasses
    // - visit implemented method declarations in interfaces
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
