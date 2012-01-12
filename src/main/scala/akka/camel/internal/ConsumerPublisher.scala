package akka.camel.internal

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.camel.internal.component.BlockingOrNotTypeConverter
import akka.camel._
import java.io.InputStream

import org.apache.camel.builder.RouteBuilder
import akka.camel.migration.Migration._

import akka.actor._
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

  val registrator = context.actorOf(Props(new Actor {

    override def postRestart(reason: Throwable) {
      println("Restarted registrator")
    }

    def receive ={
      case RegisterConsumer(endpointUri, consumer) => {
        camel.context.addRoutes(new ConsumerActorRouteBuilder(endpointUri, consumer.self, consumer))
        context.sender ! EndpointActivated(consumer.self)
        EventHandler notifyListeners EventHandler.Info(this, "published actor %s at endpoint %s" format(consumer, endpointUri))
      }

      case UnregisterConsumer(consumer) => {
        camel.context.stopRoute(consumer.path.toString)
        context.sender ! EndpointDeActivated(consumer)
        EventHandler notifyListeners EventHandler.Info(this, "unpublished actor %s from endpoint %s" format(consumer, consumer.path))
      }
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
      message match{
        case Some(RegisterConsumer(_, consumer)) => sender ! EndpointFailedToActivate(consumer.self, reason)
        case Some(UnregisterConsumer(consumer)) => sender ! EndpointFailedToDeActivate(consumer, reason)
        case _ =>
      }
    }
  }))

  def receive = {
    case msg @ RegisterConsumer(_, consumer) => unless(isAlreadyActivated(consumer.self)) {
      activated.add(consumer.self)
      registrator ! msg
    }
    case msg @ EndpointActivated(consumer) => {
      context.watch(consumer)
      context.system.eventStream.publish(msg)
    }
    case msg @ EndpointFailedToActivate(consumer, _) => {
      activated.remove(consumer)
      context.system.eventStream.publish(msg)
    }
    case Terminated(ref) => {
      activated.remove(ref)
      registrator ! UnregisterConsumer(ref)
    }
    case msg @ EndpointDeActivated(ref) => { context.system.eventStream.publish(msg) }
    case msg @ EndpointFailedToDeActivate(ref, cause) => { context.system.eventStream.publish(msg) }
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
private[camel] case class RegisterConsumer(endpointUri:String, actor: Consumer)


/**
 * Event indicating that a consumer actor has been unregistered from the actor registry.
 */
private[camel] case class UnregisterConsumer(actorRef: ActorRef)


