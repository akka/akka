/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.camel

import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.{ProcessorDefinition, RouteDefinition}

import akka.actor._
import akka.camel.component.TypedActorComponent
import akka.util.Logging

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerPublisher extends Logging {
  /**
   * Creates a route to the registered consumer actor.
   */
  def handleConsumerActorRegistered(event: ConsumerActorRegistered) {
    CamelContextManager.mandatoryContext.addRoutes(new ConsumerActorRouteBuilder(event))
    log.info("published actor %s at endpoint %s" format (event.actorRef, event.endpointUri))
  }

  /**
   * Stops the route to the already un-registered consumer actor.
   */
  def handleConsumerActorUnregistered(event: ConsumerActorUnregistered) {
    CamelContextManager.mandatoryContext.stopRoute(event.uuid)
    log.info("unpublished actor %s from endpoint %s" format (event.actorRef, event.endpointUri))
  }

  /**
   * Creates a route to an typed actor method.
   */
  def handleConsumerMethodRegistered(event: ConsumerMethodRegistered) {
    CamelContextManager.typedActorRegistry.put(event.methodUuid, event.typedActor)
    CamelContextManager.mandatoryContext.addRoutes(new ConsumerMethodRouteBuilder(event))
    log.info("published method %s of %s at endpoint %s" format (event.methodName, event.typedActor, event.endpointUri))
  }

  /**
   * Stops the route to the already un-registered consumer actor method.
   */
  def handleConsumerMethodUnregistered(event: ConsumerMethodUnregistered) {
    CamelContextManager.typedActorRegistry.remove(event.methodUuid)
    CamelContextManager.mandatoryContext.stopRoute(event.methodUuid)
    log.info("unpublished method %s of %s from endpoint %s" format (event.methodName, event.typedActor, event.endpointUri))
  }
}

/**
 * Actor that publishes consumer actors and typed actor methods at Camel endpoints.
 * The Camel context used for publishing is obtained via CamelContextManager.context.
 * This actor accepts messages of type
 * akka.camel.ConsumerActorRegistered,
 * akka.camel.ConsumerActorUnregistered,
 * akka.camel.ConsumerMethodRegistered and
 * akka.camel.ConsumerMethodUnregistered.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerPublisher extends Actor {
  import ConsumerPublisher._

  @volatile private var registrationLatch = new CountDownLatch(0)
  @volatile private var unregistrationLatch = new CountDownLatch(0)

  protected def receive = {
    case r: ConsumerActorRegistered => {
      handleConsumerActorRegistered(r)
      registrationLatch.countDown
    }
    case u: ConsumerActorUnregistered => {
      handleConsumerActorUnregistered(u)
      unregistrationLatch.countDown
    }
    case mr: ConsumerMethodRegistered => {
      handleConsumerMethodRegistered(mr)
      registrationLatch.countDown
    }
    case mu: ConsumerMethodUnregistered => {
      handleConsumerMethodUnregistered(mu)
      unregistrationLatch.countDown
    }
    case SetExpectedRegistrationCount(num) => {
      registrationLatch = new CountDownLatch(num)
      self.reply(registrationLatch)
    }
    case SetExpectedUnregistrationCount(num) => {
      unregistrationLatch = new CountDownLatch(num)
      self.reply(unregistrationLatch)
    }
    case _ => { /* ignore */}
  }
}

private[camel] case class SetExpectedRegistrationCount(num: Int)
private[camel] case class SetExpectedUnregistrationCount(num: Int)

/**
 * Abstract route to a target which is either an actor or an typed actor method.
 *
 * @param endpointUri endpoint URI of the consumer actor or typed actor method.
 * @param id actor identifier or typed actor identifier (registry key).
 *
 * @author Martin Krasser
 */
private[camel] abstract class ConsumerRouteBuilder(endpointUri: String, id: String) extends RouteBuilder {
  // TODO: make conversions configurable
  private val bodyConversions = Map(
    "file" -> classOf[InputStream]
  )

  def configure = {
    val schema = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    val cnvopt = bodyConversions.get(schema)

    onRouteDefinition(startRouteDefinition(cnvopt)).to(targetUri)
  }

  protected def routeDefinitionHandler: RouteDefinitionHandler
  protected def targetUri: String

  private def onRouteDefinition(rd: RouteDefinition) = routeDefinitionHandler.onRouteDefinition(rd)  
  private def startRouteDefinition(bodyConversion: Option[Class[_]]): RouteDefinition = bodyConversion match {
    case Some(clazz) => from(endpointUri).routeId(id).convertBodyTo(clazz)
    case None        => from(endpointUri).routeId(id)
  }
}

/**
 * Defines the route to a (untyped) consumer actor.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerActorRouteBuilder(event: ConsumerActorRegistered) extends ConsumerRouteBuilder(event.endpointUri, event.uuid) {
  protected def routeDefinitionHandler: RouteDefinitionHandler = event.routeDefinitionHandler
  protected def targetUri = "actor:uuid:%s?blocking=%s&autoack=%s" format (event.uuid, event.blocking, event.autoack)
}

/**
 * Defines the route to a typed actor method.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerMethodRouteBuilder(event: ConsumerMethodRegistered) extends ConsumerRouteBuilder(event.endpointUri, event.methodUuid) {
  protected def routeDefinitionHandler: RouteDefinitionHandler = event.routeDefinitionHandler
  protected def targetUri = "%s:%s?method=%s" format (TypedActorComponent.InternalSchema, event.methodUuid, event.methodName)
}

/**
 * A registration listener that triggers publication of consumer actors and typed actor
 * methods as well as un-publication of consumer actors and typed actor methods. This actor
 * needs to be initialized with a <code>PublishRequestorInit</code> command message for
 * obtaining a reference to a <code>publisher</code> actor. Before initialization it buffers
 * all outbound messages and delivers them to the <code>publisher</code> when receiving a
 * <code>PublishRequestorInit</code> message. After initialization, outbound messages are
 * delivered directly without buffering.
 *
 * @see PublishRequestorInit
 *
 * @author Martin Krasser
 */
private[camel] class PublishRequestor extends Actor {
  private val events = collection.mutable.Map[String, ConsumerEvent]()
  private var publisher: Option[ActorRef] = None

  protected def receive = {
    case ActorRegistered(actor) =>
      for (event <- ConsumerActorRegistered.forConsumer(actor)) deliverCurrentEvent(event)
    case ActorUnregistered(actor) =>
      for (event <- ConsumerActorUnregistered.forConsumer(actor)) deliverCurrentEvent(event)
    case AspectInitRegistered(proxy, init) =>
      for (event <- ConsumerMethodRegistered.forConsumer(proxy, init)) deliverCurrentEvent(event)
    case AspectInitUnregistered(proxy, init) =>
      for (event <- ConsumerMethodUnregistered.forConsumer(proxy, init)) deliverCurrentEvent(event)
    case PublishRequestorInit(pub) => {
      publisher = Some(pub)
      deliverBufferedEvents
    }
    case _ => { /* ignore */ }
  }

  private def deliverCurrentEvent(event: ConsumerEvent) = {
    publisher match {
      case Some(pub) => pub ! event
      case None      => events += event.uuid -> event
    }
  }

  private def deliverBufferedEvents = {
    for (event <- events.values) deliverCurrentEvent(event)
    events.clear
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object PublishRequestor {
  def pastActorRegisteredEvents =
    for {
      actor <- Actor.registry.actors
    } yield ActorRegistered(actor)

  def pastAspectInitRegisteredEvents =
    for {
      actor <- Actor.registry.typedActors
      init = AspectInitRegistry.initFor(actor)
      if (init ne null)
    } yield AspectInitRegistered(actor, init)
}

/**
 * Command message to initialize a PublishRequestor to use <code>consumerPublisher</code>
 * for publishing actors or typed actor methods.
 */
private[camel] case class PublishRequestorInit(consumerPublisher: ActorRef)

/**
 * A consumer (un)registration event.
 */
private[camel] sealed trait ConsumerEvent {
  val uuid: String
}

/**
 * A consumer actor (un)registration event.
 */
private[camel] trait ConsumerActorEvent extends ConsumerEvent {
  val actorRef: ActorRef
  val actor: Consumer

  val uuid                   = actorRef.uuid.toString
  val endpointUri            = actor.endpointUri
  val blocking               = actor.blocking
  val autoack                = actor.autoack
  val routeDefinitionHandler = actor.routeDefinitionHandler
}

/**
 * A consumer method (un)registration event.
 */
private[camel] trait ConsumerMethodEvent extends ConsumerEvent {
  val typedActor: AnyRef
  val init: AspectInit
  val method: Method

  val uuid = init.actorRef.uuid.toString
  val methodName = method.getName
  val methodUuid = "%s_%s" format (uuid, methodName)

  lazy val routeDefinitionHandler = consumeAnnotation.routeDefinitionHandler.newInstance
  lazy val consumeAnnotation = method.getAnnotation(classOf[consume])
  lazy val endpointUri = consumeAnnotation.value
}

/**
 * Event indicating that a consumer actor has been registered at the actor registry.
 */
private[camel] case class ConsumerActorRegistered(actorRef: ActorRef, actor: Consumer) extends ConsumerActorEvent

/**
 * Event indicating that a consumer actor has been unregistered from the actor registry.
 */
private[camel] case class ConsumerActorUnregistered(actorRef: ActorRef, actor: Consumer) extends ConsumerActorEvent

/**
 * Event indicating that an typed actor proxy has been created for a typed actor. For each <code>@consume</code>
 * annotated typed actor method a separate instance of this class is created.
 */
private[camel] case class ConsumerMethodRegistered(typedActor: AnyRef, init: AspectInit, method: Method) extends ConsumerMethodEvent

/**
 * Event indicating that an typed actor has been stopped. For each <code>@consume</code>
 * annotated typed object method a separate instance of this class is created.
 */
private[camel] case class ConsumerMethodUnregistered(typedActor: AnyRef, init: AspectInit, method: Method) extends ConsumerMethodEvent

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerActorRegistered {
  /**
   * Creates an ConsumerActorRegistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def forConsumer(actorRef: ActorRef): Option[ConsumerActorRegistered] = {
    Consumer.forConsumer[ConsumerActorRegistered](actorRef) {
      actor => ConsumerActorRegistered(actorRef, actor)
    }
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerActorUnregistered {
  /**
   * Creates an ConsumerActorUnregistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def forConsumer(actorRef: ActorRef): Option[ConsumerActorUnregistered] = {
    Consumer.forConsumer[ConsumerActorUnregistered](actorRef) {
      actor => ConsumerActorUnregistered(actorRef, actor)
    }
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerMethod {
  /**
   * Applies a function <code>f</code> to each consumer method of <code>TypedActor</code> and
   * returns the function results as a list. A consumer method is one that is annotated with
   * <code>@consume</code>. If <code>typedActor</code> is a proxy for a remote typed actor
   * <code>f</code> is never called and <code>Nil</code> is returned.
   */
  def forConsumer[T](typedActor: AnyRef, init: AspectInit)(f: Method => T): List[T] = {
    if (init.remoteAddress.isDefined) Nil // let remote node publish typed actor methods on endpoints
    else {
      // TODO: support consumer annotation inheritance
      // - visit overridden methods in superclasses
      // - visit implemented method declarations in interfaces
      val intfClass = typedActor.getClass
      val implClass = init.targetInstance.getClass
      (for (m <- intfClass.getMethods.toList; if (m.isAnnotationPresent(classOf[consume]))) yield f(m)) ++
      (for (m <- implClass.getMethods.toList; if (m.isAnnotationPresent(classOf[consume]))) yield f(m))
    }
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerMethodRegistered {
  /**
   * Creates a list of ConsumerMethodRegistered event messages for a typed actor or an empty
   * list if the typed actor is a proxy for a remote typed actor or the typed actor doesn't
   * have any <code>@consume</code> annotated methods.
   */
  def forConsumer(typedActor: AnyRef, init: AspectInit): List[ConsumerMethodRegistered] = {
    ConsumerMethod.forConsumer(typedActor, init) {
      m => ConsumerMethodRegistered(typedActor, init, m)
    }
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerMethodUnregistered {
  /**
   * Creates a list of ConsumerMethodUnregistered event messages for a typed actor or an empty
   * list if the typed actor is a proxy for a remote typed actor or the typed actor doesn't
   * have any <code>@consume</code> annotated methods.
   */
  def forConsumer(typedActor: AnyRef, init: AspectInit): List[ConsumerMethodUnregistered] = {
    ConsumerMethod.forConsumer(typedActor, init) {
      m => ConsumerMethodUnregistered(typedActor, init, m)
    }
  }
}
