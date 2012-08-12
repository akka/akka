/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal

import akka.camel._
import component.CamelPath
import java.io.InputStream

import org.apache.camel.builder.RouteBuilder

import akka.actor._
import collection.mutable
import org.apache.camel.model.RouteDefinition
import org.apache.camel.CamelContext
import scala.concurrent.util.Duration
import concurrent.Await
import akka.util.Timeout

/**
 * For internal use only.
 * Manages consumer registration. Consumers call registerConsumer method to register themselves  when they get created.
 * ActorEndpoint uses it to lookup an actor by its path.
 */
private[camel] trait ConsumerRegistry { this: Activation ⇒
  def system: ActorSystem
  def context: CamelContext

  /**
   * For internal use only.
   */
  private[this] lazy val idempotentRegistry = system.actorOf(Props(new IdempotentCamelConsumerRegistry(context)))
  /**
   * For internal use only. BLOCKING
   * @param endpointUri the URI to register the consumer on
   * @param consumer the consumer
   * @param activationTimeout the timeout for activation
   * @return the actorRef to the consumer
   */
  private[camel] def registerConsumer(endpointUri: String, consumer: Consumer, activationTimeout: Duration) = {
    idempotentRegistry ! RegisterConsumer(endpointUri, consumer.self, consumer)
    Await.result(activationFutureFor(consumer.self)(activationTimeout), activationTimeout)
  }
}

/**
 * For internal use only.
 * Guarantees idempotent registration of camel consumer endpoints.
 *
 * Once registered the consumer is watched and unregistered upon termination.
 * It also publishes events to the eventStream so interested parties could subscribe to them.
 * The main consumer of these events is currently the ActivationTracker.
 */
private[camel] class IdempotentCamelConsumerRegistry(camelContext: CamelContext) extends Actor {

  case class UnregisterConsumer(actorRef: ActorRef)

  val activated = new mutable.HashSet[ActorRef]

  val registrator = context.actorOf(Props(new CamelConsumerRegistrator))

  def receive = {
    case msg @ RegisterConsumer(_, consumer, _) ⇒
      if (!isAlreadyActivated(consumer)) {
        activated.add(consumer)
        registrator ! msg
      }
    case msg @ EndpointActivated(consumer) ⇒
      context.watch(consumer)
      context.system.eventStream.publish(msg)
    case msg @ EndpointFailedToActivate(consumer, _) ⇒
      activated.remove(consumer)
      context.system.eventStream.publish(msg)
    case Terminated(ref) ⇒
      activated.remove(ref)
      registrator ! UnregisterConsumer(ref)
    case msg @ EndpointFailedToDeActivate(ref, cause) ⇒ context.system.eventStream.publish(msg)
    case msg: EndpointDeActivated                     ⇒ context.system.eventStream.publish(msg)
  }

  def isAlreadyActivated(ref: ActorRef): Boolean = activated.contains(ref)

  //FIXME Break out
  class CamelConsumerRegistrator extends Actor with ActorLogging {

    def receive = {
      case RegisterConsumer(endpointUri, consumer, consumerConfig) ⇒
        camelContext.addRoutes(new ConsumerActorRouteBuilder(endpointUri, consumer, consumerConfig))
        context.sender ! EndpointActivated(consumer)
        log.debug("Published actor [{}] at endpoint [{}]", consumerConfig, endpointUri)
      case UnregisterConsumer(consumer) ⇒
        camelContext.stopRoute(consumer.path.toString)
        camelContext.removeRoute(consumer.path.toString)
        context.sender ! EndpointDeActivated(consumer)
        log.debug("Unpublished actor [{}] from endpoint [{}]", consumer, consumer.path)
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
      //FIXME check logic
      super.preStart()
      message match {
        case Some(RegisterConsumer(_, consumer, _)) ⇒ sender ! EndpointFailedToActivate(consumer, reason)
        case Some(UnregisterConsumer(consumer))     ⇒ sender ! EndpointFailedToDeActivate(consumer, reason)
        case _                                      ⇒
      }
    }
  }
}

/**
 * For internal use only. A message to register a consumer.
 * @param endpointUri the endpointUri to register to
 * @param actorRef the actorRef to register as a consumer
 * @param config the configuration for the consumer
 */
private[camel] case class RegisterConsumer(endpointUri: String, actorRef: ActorRef, config: ConsumerConfig)

/**
 * For internal use only.
 * Builder of a route to a target which can be an actor.
 *
 * @param endpointUri endpoint URI of the consumer actor.
 *
 * @author Martin Krasser
 */
private[camel] class ConsumerActorRouteBuilder(endpointUri: String, consumer: ActorRef, config: ConsumerConfig) extends RouteBuilder {

  protected def targetActorUri = CamelPath.toUri(consumer, config.autoAck, config.replyTimeout)

  def configure() {
    val scheme = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."

    val route = from(endpointUri).routeId(consumer.path.toString)
    val converted = Conversions(scheme, route)
    val userCustomized = applyUserRouteCustomization(converted)
    userCustomized.to(targetActorUri)
  }

  def applyUserRouteCustomization(rd: RouteDefinition) = config.onRouteDefinition(rd)

  object Conversions {
    private val bodyConversions = Map(
      "file" -> classOf[InputStream])

    def apply(scheme: String, routeDefinition: RouteDefinition): RouteDefinition = bodyConversions.get(scheme) match {
      case Some(clazz) ⇒ routeDefinition.convertBodyTo(clazz)
      case None        ⇒ routeDefinition
    }
  }

}