/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.camel

import java.io.InputStream

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.RouteDefinition
import akka.camel.migration.Migration._

import akka.actor._


/**
 * TODO I would remove this code alltogether. You can addRoutes and stopRoutes through the extension in the Consumer trait.
 * Publishes consumer actors on <code>ConsumerActorRegistered</code> events and unpublishes
 * consumer actors on <code>ConsumerActorUnregistered</code> events. Publications are tracked
 * by sending an <code>activationTracker</code> an <code>EndpointActivated</code> event,
 * unpublications are tracked by sending an <code>EndpointActivated</code> event.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerPublisher(camel : Camel) extends Actor {
  def receive = {
    case r: ConsumerActorRegistered => {
      camel.addRoutes(new ConsumerActorRouteBuilder(r))
      sender ! EndpointActivated
      EventHandler notifyListeners EventHandler.Info(this, "published actor %s at endpoint %s" format (r.actorRef, r.endpointUri))

    }
    case u: ConsumerActorUnregistered => {
      camel.stopRoute(u.actorRef.path.toString)
      sender ! EndpointDeActivated
      EventHandler notifyListeners EventHandler.Info(this, "unpublished actor %s from endpoint %s" format (u.actorRef, u.actorRef.path))
    }
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
  //TODO this should use Actor Path. blocking, autoack etc is configured in the actor. (I think you've done that already, and this might be dead code :)
  //(PG) not sure what you mean. This is an actor endpoint uri, so if camel wants to create it needs the config.
  // We could fetch the config from an actor but this would require a message exchange.
  // Alternatively we could scrap the idea of actor component and just register a processor. That's 30 lines of code but it has its limitations.

  protected def targetUri = "actor:path:%s?blocking=%s&autoack=%s&outTimeout=%s" format (event.path, event.blocking, event.autoack, event.outTimeout.toNanos)
}

//TODO I don't think any of these events are necessary anymore.
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
private[camel] case class EndpointActivated(actorRef : ActorRef)
private[camel] case class EndpointDeActivated(actorRef : ActorRef)

