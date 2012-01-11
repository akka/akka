/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.camel

import java.io.InputStream

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.RouteDefinition
import akka.camel.migration.Migration._

import akka.actor._
import akka.util.ErrorUtils._
import collection.mutable

/**
 *
 * Publishes consumer actors on <code>ConsumerActorRegistered</code> events and unpublishes
 * consumer actors on <code>ConsumerActorUnregistered</code> events. Publications are tracked
 * by sending an <code>activationTracker</code> an <code>EndpointActivated</code> event,
 * unpublications are tracked by sending an <code>EndpointActivated</code> event.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerPublisher(camel : Camel) extends Actor {
  val activated  = new mutable.HashSet[ActorRef]

  def receive = {
    case r: ConsumerActorRegistered => unless(isAlreadyActivated(r.actorRef)) { registerConsumer(r) }
    case Terminated(ref) => {
      activated.remove(ref)
      try_(camel.stopRoute(ref.path.toString)) match {
        case Right(_) =>{
          context.system.eventStream.publish(EndpointDeActivated(ref))
          EventHandler notifyListeners EventHandler.Info(this, "unpublished actor %s from endpoint %s" format(ref, ref.path))
        }
        case Left(e) => context.system.eventStream.publish(EndpointFailedToDeActivate(ref, e)) //TODO: is there anything better we could do?

      }

    }
  }


  def registerConsumer(r: ConsumerActorRegistered) {
    try_(camel.addRoutes(new ConsumerActorRouteBuilder(r))) match {
      case Right(_) => {
        context.watch(r.actorRef)
        activated.add(r.actorRef)
        context.system.eventStream.publish(EndpointActivated(r.actorRef))
        EventHandler notifyListeners EventHandler.Info(this, "published actor %s at endpoint %s" format(r.actorRef, r.endpointUri))
      }
      case Left(throwable) => {
        context.system.eventStream.publish(EndpointFailedToActivate(r.actorRef, throwable))
      }
    }
  }

  def unless[A](condition: Boolean)(block  : => A) = if (!condition) block

  def isAlreadyActivated(ref: ActorRef): Boolean = activated.contains(ref)

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
 * Builder of a route to a consumer actor.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerActorRouteBuilder(event: ConsumerActorRegistered) extends ConsumerRouteBuilder(event.endpointUri, event.path.toString) {
  protected def routeDefinitionHandler: RouteDefinitionHandler = event.routeDefinitionHandler
  //TODO: what if actorpath contains parameters? Should we use url encoding? But this will look ugly...
  protected def targetUri = "actor:path:%s?blocking=%s&autoack=%s&outTimeout=%s" format (event.path, event.blocking, event.autoack, event.outTimeout.toNanos)
}

/**
 * Event indicating that a consumer actor has been registered at the actor registry.
 */
private[camel] case class ConsumerActorRegistered(endpointUri:String, actor: Consumer){
  def actorRef               = actor.self
  def path                   = actorRef.path
  def outTimeout             = actor.outTimeout
  def blocking               = actor.blocking
  def autoack                = actor.autoack
  def routeDefinitionHandler = actor.routeDefinitionHandler
}

/**
 * Event indicating that a consumer actor has been unregistered from the actor registry.
 */
private[camel] case class ConsumerActorUnregistered(actorRef: ActorRef)

/**
 * Event message indicating that a single endpoint has been activated.
 */
private[camel] case class EndpointActivated(actorRef : ActorRef) extends ActivationMessage(actorRef)
private[camel] case class EndpointFailedToActivate(actorRef : ActorRef, cause : Throwable) extends ActivationMessage(actorRef)
private[camel] case class EndpointDeActivated(actorRef : ActorRef) extends ActivationMessage(actorRef)
private[camel] case class EndpointFailedToDeActivate(actorRef : ActorRef, cause : Throwable) extends ActivationMessage(actorRef)

