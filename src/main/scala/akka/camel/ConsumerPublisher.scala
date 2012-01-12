/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.camel

import component.BlockingOrNotTypeConverter
import java.io.InputStream

import org.apache.camel.builder.RouteBuilder
import akka.camel.migration.Migration._

import akka.actor._
import akka.util.ErrorUtils._
import collection.mutable
import org.apache.camel.model.{ProcessorDefinition, RouteDefinition}

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
    case r: ConsumerActorRegistered => unless(isAlreadyActivated(r.actor.self)) { registerConsumer(r.endpointUri, r.actor.self, r.actor) }
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

  def registerConsumer(endpointUri:String,  consumer:ActorRef, config: ConsumerConfig) {
    try_(camel.addRoutes(new ConsumerActorRouteBuilder(endpointUri, consumer, config))) match {
      case Right(_) => {
        context.watch(consumer)
        activated.add(consumer)
        context.system.eventStream.publish(EndpointActivated(consumer))
        EventHandler notifyListeners EventHandler.Info(this, "published actor %s at endpoint %s" format(consumer, endpointUri))
      }
      case Left(throwable) => {
        context.system.eventStream.publish(EndpointFailedToActivate(consumer, throwable))
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

  protected def targetUri: String

  def onRouteDefinition(rd: RouteDefinition) : ProcessorDefinition[_]
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
private[camel] class ConsumerActorRouteBuilder(endpointUri: String, consumer : ActorRef,  config: ConsumerConfig) extends ConsumerRouteBuilder(endpointUri, consumer.path.toString) {
  def onRouteDefinition(rd: RouteDefinition) = config.onRouteDefinition(rd)
  //TODO: what if actorpath contains parameters? Should we use url encoding? But this will look ugly...
  protected def targetUri = "actor:path:%s?blocking=%s&autoack=%s&outTimeout=%s" format (consumer.path, BlockingOrNotTypeConverter.toString(config.blocking), config.autoack, config.outTimeout.toNanos)

}

/**
 * Event indicating that a consumer actor has been registered at the actor registry.
 */
private[camel] case class ConsumerActorRegistered(endpointUri:String, actor: Consumer)


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

