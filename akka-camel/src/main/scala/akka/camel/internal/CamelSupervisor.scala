package akka.camel.internal

import akka.actor._
import akka.camel.{ CamelSupport, ConsumerConfig }
import org.apache.camel.Endpoint
import org.apache.camel.processor.SendProcessor
import scala.util.control.NonFatal
import akka.actor.Terminated
import akka.actor.SupervisorStrategy.Resume
import akka.camel.internal.CamelSupervisor._
import akka.AkkaException
import akka.camel.internal.ActivationProtocol._

/**
 * For internal use only.
 * Top level supervisor for internal Camel actors
 */
private[camel] class CamelSupervisor extends Actor with CamelSupport {
  private val activationTracker = context.actorOf(Props[ActivationTracker], "activationTracker")
  private val registry: ActorRef = context.actorOf(Props(new Registry(activationTracker)), "registry")

  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) ⇒
      Resume
  }

  def receive = {
    case AddWatch(actorRef)     ⇒ context.watch(actorRef)
    case Terminated(actorRef)   ⇒ registry ! DeRegister(actorRef)
    case msg: ActivationMessage ⇒ activationTracker forward msg
    case msg                    ⇒ registry forward (msg)
  }
}

/**
 * For internal use only.
 * Messages for the camel supervisor, registrations and de-registrations.
 */
private[camel] object CamelSupervisor {

  @SerialVersionUID(1L)
  sealed trait CamelSupervisorMessage extends Serializable

  /**
   * For internal use only.
   * Registers a consumer or a producer.
   */
  case class Register(actorRef: ActorRef, endpointUri: String, config: Option[ConsumerConfig] = None) extends NoSerializationVerificationNeeded

  /**
   * For internal use only.
   * De-registers a producer or a consumer.
   */
  @SerialVersionUID(1L)
  case class DeRegister(actorRef: ActorRef) extends CamelSupervisorMessage

  /**
   * For internal use only.
   * Adds a watch for the actor
   */
  @SerialVersionUID(1L)
  case class AddWatch(actorRef: ActorRef) extends CamelSupervisorMessage

  /**
   * For internal use only.
   * Provides a Producer with the required camel objects to function.
   */
  case class CamelProducerObjects(endpoint: Endpoint, processor: SendProcessor) extends NoSerializationVerificationNeeded
}

/**
 * For internal use only.
 * Thrown by registrars to indicate that the actor could not be de-activated.
 */
private[camel] class ActorDeActivationException(val actorRef: ActorRef, cause: Throwable) extends AkkaException("Actor [%s] failed to de-activate".format(actorRef), cause)

/**
 * For internal use only.
 * Thrown by the registrars to indicate that the actor could not be activated.
 */
private[camel] class ActorActivationException(val actorRef: ActorRef, cause: Throwable) extends AkkaException("Actor [%s] failed to activate".format(actorRef), cause)

/**
 * For internal use only.
 * Registry for Camel Consumers and Producers. Supervises the registrars.
 */
private[camel] class Registry(activationTracker: ActorRef) extends Actor with CamelSupport {
  import context.{ stop, parent }

  private val producerRegistrar = context.actorOf(Props(new ProducerRegistrar(activationTracker)), "producerRegistrar")
  private val consumerRegistrar = context.actorOf(Props(new ConsumerRegistrar(activationTracker)), "consumerRegistrar")
  private var producers = Set[ActorRef]()
  private var consumers = Set[ActorRef]()

  override val supervisorStrategy = OneForOneStrategy() {
    case e: ActorActivationException ⇒
      activationTracker ! EndpointFailedToActivate(e.actorRef, e.getCause)
      stop(e.actorRef)
      Resume
    case e: ActorDeActivationException ⇒
      activationTracker ! EndpointFailedToDeActivate(e.actorRef, e.getCause)
      stop(e.actorRef)
      Resume
    case NonFatal(e) ⇒
      Resume
  }

  def receive = {
    case msg @ Register(consumer, _, Some(_)) ⇒
      if (!consumers(consumer)) {
        consumers += consumer
        consumerRegistrar forward msg
        parent ! AddWatch(consumer)
      }
    case msg @ Register(producer, _, None) ⇒
      if (!producers(producer)) {
        producers += producer
        producerRegistrar forward msg
        parent ! AddWatch(producer)
      }
    case DeRegister(actorRef) ⇒
      producers.find(_ == actorRef).foreach { p ⇒
        deRegisterProducer(p)
        producers -= p
      }
      consumers.find(_ == actorRef).foreach { c ⇒
        deRegisterConsumer(c)
        consumers -= c
      }
  }

  private def deRegisterConsumer(actorRef: ActorRef) { consumerRegistrar ! DeRegister(actorRef) }

  private def deRegisterProducer(actorRef: ActorRef) { producerRegistrar ! DeRegister(actorRef) }
}

/**
 * For internal use only.
 * Registers Producers.
 */
private[camel] class ProducerRegistrar(activationTracker: ActorRef) extends Actor with CamelSupport {
  private var camelObjects = Map[ActorRef, (Endpoint, SendProcessor)]()

  def receive = {
    case Register(producer, endpointUri, _) ⇒
      if (!camelObjects.contains(producer)) {
        try {
          val endpoint = camelContext.getEndpoint(endpointUri)
          val processor = new SendProcessor(endpoint)
          camelObjects += producer -> (endpoint, processor)
          // if this throws, the supervisor stops the producer and de-registers it on termination
          processor.start()
          producer ! CamelProducerObjects(endpoint, processor)
          activationTracker ! EndpointActivated(producer)
        } catch {
          case NonFatal(e) ⇒ throw new ActorActivationException(producer, e)
        }
      }
    case DeRegister(producer) ⇒
      camelObjects.get(producer).foreach {
        case (_, processor) ⇒
          try {
            camelObjects.get(producer).foreach(_._2.stop())
            camelObjects -= producer
            activationTracker ! EndpointDeActivated(producer)
          } catch {
            case NonFatal(e) ⇒ throw new ActorDeActivationException(producer, e)
          }
      }
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
        // if this throws, the supervisor stops the consumer and de-registers it on termination
        camelContext.addRoutes(new ConsumerActorRouteBuilder(endpointUri, consumer, consumerConfig, camel.settings))
        activationTracker ! EndpointActivated(consumer)
      } catch {
        case NonFatal(e) ⇒ throw new ActorActivationException(consumer, e)
      }
    case DeRegister(consumer) ⇒
      try {
        val route = consumer.path.toString
        camelContext.stopRoute(route)
        camelContext.removeRoute(route)
        activationTracker ! EndpointDeActivated(consumer)
      } catch {
        case NonFatal(e) ⇒ throw new ActorDeActivationException(consumer, e)
      }
  }
}