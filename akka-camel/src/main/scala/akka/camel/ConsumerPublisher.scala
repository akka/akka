/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.camel

import java.io.InputStream
import java.util.concurrent.CountDownLatch

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.RouteDefinition

import akka.actor._
import akka.event.EventHandler

/**
 * Concrete publish requestor that requests publication of consumer actors on <code>ActorRegistered</code>
 * events and unpublication of consumer actors on <code>ActorUnregistered</code> events.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerPublishRequestor extends PublishRequestor {
  def receiveActorRegistryEvent = {
    case ActorRegistered(_, actor, None)   ⇒ for (event ← ConsumerActorRegistered.eventFor(actor)) deliverCurrentEvent(event)
    case ActorUnregistered(_, actor, None) ⇒ for (event ← ConsumerActorUnregistered.eventFor(actor)) deliverCurrentEvent(event)
    case _                                 ⇒ ()
  }
}

/**
 * Publishes consumer actors on <code>ConsumerActorRegistered</code> events and unpublishes
 * consumer actors on <code>ConsumerActorUnregistered</code> events. Publications are tracked
 * by sending an <code>activationTracker</code> an <code>EndpointActivated</code> event,
 * unpublications are tracked by sending an <code>EndpointActivated</code> event.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerPublisher(activationTracker: ActorRef) extends Actor {
  import ConsumerPublisher._

  def receive = {
    case r: ConsumerActorRegistered ⇒ {
      handleConsumerActorRegistered(r)
      activationTracker ! EndpointActivated
    }
    case u: ConsumerActorUnregistered ⇒ {
      handleConsumerActorUnregistered(u)
      activationTracker ! EndpointDeactivated
    }
    case _ ⇒ { /* ignore */ }
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerPublisher {
  /**
   * Creates a route to the registered consumer actor.
   */
  def handleConsumerActorRegistered(event: ConsumerActorRegistered) {
    CamelContextManager.mandatoryContext.addRoutes(new ConsumerActorRouteBuilder(event))
    EventHandler notifyListeners EventHandler.Info(this, "published actor %s at endpoint %s" format (event.actorRef, event.endpointUri))
  }

  /**
   * Stops the route to the already un-registered consumer actor.
   */
  def handleConsumerActorUnregistered(event: ConsumerActorUnregistered) {
    CamelContextManager.mandatoryContext.stopRoute(event.uuid)
    EventHandler notifyListeners EventHandler.Info(this, "unpublished actor %s from endpoint %s" format (event.actorRef, event.endpointUri))
  }
}

/**
 * Abstract builder of a route to a target which can be either an actor or an typed actor method.
 *
 * @param endpointUri endpoint URI of the consumer actor or typed actor method.
 * @param id unique route identifier.
 *
 * @author Martin Krasser
 */
private[camel] abstract class ConsumerRouteBuilder(endpointUri: String, id: String) extends RouteBuilder {
  // TODO: make conversions configurable
  private val bodyConversions = Map(
    "file" -> classOf[InputStream])

  def configure = {
    val schema = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    val cnvopt = bodyConversions.get(schema)

    onRouteDefinition(startRouteDefinition(cnvopt)).to(targetUri)
  }

  protected def routeDefinitionHandler: RouteDefinitionHandler
  protected def targetUri: String

  private def onRouteDefinition(rd: RouteDefinition) = routeDefinitionHandler.onRouteDefinition(rd)
  private def startRouteDefinition(bodyConversion: Option[Class[_]]): RouteDefinition = bodyConversion match {
    case Some(clazz) ⇒ from(endpointUri).routeId(id).convertBodyTo(clazz)
    case None        ⇒ from(endpointUri).routeId(id)
  }
}

/**
 * Builder of a route to a consumer actor.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerActorRouteBuilder(event: ConsumerActorRegistered) extends ConsumerRouteBuilder(event.endpointUri, event.uuid) {
  protected def routeDefinitionHandler: RouteDefinitionHandler = event.routeDefinitionHandler
  protected def targetUri = "actor:uuid:%s?blocking=%s&autoack=%s" format (event.uuid, event.blocking, event.autoack)
}

/**
 * Tracks <code>EndpointActivated</code> and <code>EndpointDectivated</code> events. Used to wait for a
 * certain number of endpoints activations and de-activations to occur.
 *
 * @see SetExpectedActivationCount
 * @see SetExpectedDeactivationCount
 *
 * @author Martin Krasser
 */
private[camel] class ActivationTracker extends Actor {
  private var activationLatch = new CountDownLatch(0)
  private var deactivationLatch = new CountDownLatch(0)

  def receive = {
    case SetExpectedActivationCount(num) ⇒ {
      activationLatch = new CountDownLatch(num)
      reply(activationLatch)
    }
    case SetExpectedDeactivationCount(num) ⇒ {
      deactivationLatch = new CountDownLatch(num)
      reply(deactivationLatch)
    }
    case EndpointActivated   ⇒ activationLatch.countDown
    case EndpointDeactivated ⇒ deactivationLatch.countDown
  }
}

/**
 * Command message that sets the number of expected endpoint activations on <code>ActivationTracker</code>.
 */
private[camel] case class SetExpectedActivationCount(num: Int)

/**
 * Command message that sets the number of expected endpoint de-activations on <code>ActivationTracker</code>.
 */
private[camel] case class SetExpectedDeactivationCount(num: Int)

/**
 * Event message indicating that a single endpoint has been activated.
 */
private[camel] case class EndpointActivated()

/**
 * Event message indicating that a single endpoint has been de-activated.
 */
private[camel] case class EndpointDeactivated()

/**
 * A consumer (un)registration event.
 */
private[camel] trait ConsumerEvent {
  val uuid: String
}

/**
 * A consumer actor (un)registration event.
 */
private[camel] trait ConsumerActorEvent extends ConsumerEvent {
  val actorRef: ActorRef
  val actor: Consumer

  val uuid = actorRef.uuid.toString
  val endpointUri = actor.endpointUri
  val blocking = actor.blocking
  val autoack = actor.autoack
  val routeDefinitionHandler = actor.routeDefinitionHandler
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
 * @author Martin Krasser
 */
private[camel] object ConsumerActorRegistered {
  /**
   * Creates an ConsumerActorRegistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def eventFor(actorRef: ActorRef): Option[ConsumerActorRegistered] = {
    Consumer.withConsumer[ConsumerActorRegistered](actorRef) { actor ⇒
      ConsumerActorRegistered(actorRef, actor)
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
  def eventFor(actorRef: ActorRef): Option[ConsumerActorUnregistered] = {
    Consumer.withConsumer[ConsumerActorUnregistered](actorRef) { actor ⇒
      ConsumerActorUnregistered(actorRef, actor)
    }
  }
}

