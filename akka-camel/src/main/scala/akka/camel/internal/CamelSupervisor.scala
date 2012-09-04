package akka.camel.internal

import akka.actor._
import akka.camel.{ CamelSupport, ConsumerConfig }
import org.apache.camel.Endpoint
import org.apache.camel.processor.SendProcessor
import util.control.NonFatal
import collection.mutable
import akka.actor.Terminated
import scala.Some
import akka.actor.SupervisorStrategy.{ Stop, Resume }

/**
 * For internal use only.
 * Top level supervisor for internal Camel actors
 */
private[camel] class CamelSupervisor extends Actor with CamelSupport {
  private val activationTracker = context.actorOf(Props[ActivationTracker], "activationTracker")
  private val registry: ActorRef = context.actorOf(Props(new Registry(activationTracker)), "registry")

  def receive = {
    case AddWatch(actorRef)     ⇒ context.watch(actorRef)
    case Terminated(actorRef)   ⇒ registry ! DeRegister(actorRef)
    case msg: ActivationMessage ⇒ activationTracker forward msg
    case msg                    ⇒ registry forward (msg)
  }
}

/**
 * For internal use only.
 * Registers a consumer or a producer.
 */
private[camel] case class Register(actorRef: ActorRef, endpointUri: String, config: Option[ConsumerConfig] = None)

/**
 * For internal use only.
 * De-registers a producer or a consumer.
 */
private[camel] case class DeRegister(actorRef: ActorRef)

/**
 * For internal use only.
 * Adds a watch for the actor
 */
private[camel] case class AddWatch(actorRef: ActorRef)

/**
 * For internal use only.
 * Thrown by registrars to indicate that the actor could not be de-activated.
 */
private[camel] class ActorDeActivationException(val actorRef: ActorRef, val cause: Throwable) extends Exception(cause) {
  override def getMessage: String = "Actor [%s] failed to de-activate".format(actorRef)
}

/**
 * For internal use only.
 * Thrown by the registrars to indicate that the actor could not be activated.
 */
private[camel] class ActorActivationException(val actorRef: ActorRef, val cause: Throwable) extends Exception(cause) {
  override def getMessage: String = "Actor [%s] failed to activate".format(actorRef)
}

/**
 * For internal use only.
 * Registry for Camel Consumers and Producers. Supervises the registrars.
 */
private[camel] class Registry(activationTracker: ActorRef) extends Actor with CamelSupport {
  private val producerRegistrar = context.actorOf(Props(new ProducerRegistrar(activationTracker)), "producerRegistrar")
  private val consumerRegistrar = context.actorOf(Props(new ConsumerRegistrar(activationTracker)), "consumerRegistrar")

  // map from registered actors to the deRegister functions.
  private val actors = mutable.Map[ActorRef, ActorRef ⇒ Unit]()

  override val supervisorStrategy = OneForOneStrategy() {
    case e: ActorActivationException ⇒
      activationTracker ! EndpointFailedToActivate(e.actorRef, e.getCause)
      Stop
    case e: ActorDeActivationException ⇒
      activationTracker ! EndpointFailedToDeActivate(e.actorRef, e.getCause)
      Stop
    case NonFatal(e) ⇒
      Resume
  }

  def receive = {
    case msg @ Register(consumer, _, Some(_)) ⇒
      if (!actors.contains(consumer)) {
        actors += consumer -> deRegisterConsumer
        consumerRegistrar forward msg
        context.parent ! AddWatch(consumer)
      }
    case msg @ Register(producer, _, None) ⇒
      if (!actors.contains(producer)) {
        actors += producer -> deRegisterProducer
        producerRegistrar forward msg
        context.parent ! AddWatch(producer)
      }
    case DeRegister(actorRef) ⇒
      if (actors.contains(actorRef)) {
        actors.get(actorRef).foreach(_(actorRef))
        actors.remove(actorRef)
      }
    case _ ⇒ ()
  }

  private def deRegisterConsumer(actorRef: ActorRef) { consumerRegistrar ! DeRegister(actorRef) }

  private def deRegisterProducer(actorRef: ActorRef) { producerRegistrar ! DeRegister(actorRef) }
}

/**
 * For internal use only.
 * Registers Producers.
 */
private[camel] class ProducerRegistrar(activationTracker: ActorRef) extends Actor with CamelSupport {
  private val camelObjects = new mutable.HashMap[ActorRef, (Endpoint, SendProcessor)]()

  def receive = {
    case Register(producer, endpointUri, _) ⇒
      if (!camelObjects.contains(producer)) {
        try {
          val endpoint = camelContext.getEndpoint(endpointUri)
          val processor = new SendProcessor(endpoint)
          camelObjects += producer -> (endpoint, processor)
          processor.start()
          producer ! (endpoint, processor)
          activationTracker ! EndpointActivated(producer)
        } catch {
          case NonFatal(e) ⇒ throw new ActorActivationException(producer, e)
        }
      }
    case DeRegister(producer) ⇒
      camelObjects.get(producer).foreach {
        case (_, processor) ⇒
          try {
            camelObjects.remove(producer).foreach(_._2.stop())
            activationTracker ! EndpointDeActivated(producer)
          } catch {
            case NonFatal(e) ⇒ throw new ActorDeActivationException(producer, e)
          }
      }
    case _ ⇒ ()
  }
}

/**
 * For internal use only.
 * Registers Consumers.
 */
private[camel] class ConsumerRegistrar(activationTracker: ActorRef) extends Actor with CamelSupport {
  def receive = {
    case Register(consumer, endpointUri, Some(consumerConfig)) ⇒
      try {
        camelContext.addRoutes(new ConsumerActorRouteBuilder(endpointUri, consumer, consumerConfig))
        activationTracker ! EndpointActivated(consumer)
      } catch {
        case NonFatal(e) ⇒ throw new ActorActivationException(consumer, e)
      }
    case DeRegister(consumer) ⇒
      try {
        camelContext.stopRoute(consumer.path.toString)
        camelContext.removeRoute(consumer.path.toString)
        activationTracker ! EndpointDeActivated(consumer)
      } catch {
        case NonFatal(e) ⇒ throw new ActorDeActivationException(consumer, e)
      }
    case _ ⇒ ()
  }
}