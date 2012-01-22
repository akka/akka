/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.component.{ CommunicationStyleTypeConverter, DurationTypeConverter, ActorComponent }
import internal._
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.{ ProducerTemplate, CamelContext, Endpoint }
import org.apache.camel.processor.SendProcessor
import akka.util.Duration
import akka.event.Logging.Info
import DangerousStuff._
import java.util.concurrent.ConcurrentHashMap
import akka.actor._

trait Camel extends ConsumerRegistry with ProducerRegistry with Extension with Activation {
  def context: CamelContext
  def template: ProducerTemplate

  /**
   * Refers back to the associated ActorSystem
   */
  def system: ActorSystem
}

/**
 * Creates an instance of Camel subsystem.
 *
 * @param system is used to create internal actors needed by camel instance.
 * Camel doesn't maintain the lifecycle of this actorSystem. It has to be shut down by the user.
 * In typical scenario, when camel is used with akka extension, it is natural that camel reuses the  actor system it extends.
 * Also by not creating extra internal actor system we are conserving resources.
 */
class DefaultCamel(val system: ActorSystem) extends Camel {
  lazy val context: CamelContext = {
    val ctx = new DefaultCamelContext
    ctx.setName(system.name);
    ctx.setStreamCaching(true)
    ctx.addComponent("actor", new ActorComponent(this))
    ctx.getTypeConverterRegistry.addTypeConverter(classOf[CommunicationStyle], classOf[String], CommunicationStyleTypeConverter)
    ctx.getTypeConverterRegistry.addTypeConverter(classOf[Duration], classOf[String], DurationTypeConverter)
    ctx
  }

  lazy val template = context.createProducerTemplate()

  /**
   * Starts camel and underlying camel context and template.
   * Only the creator of Camel should start and stop it.
   * @see akka.camel.DefaultCamel#stop()
   */
  //TODO consider starting Camel during initialization to avoid lifecycle issues. This would require checking if we are not limiting context configuration after it's started.
  def start = {
    context.start()
    try_(template.start()) otherwise context.stop()
    //TODO use proper akka logging
    system.eventStream.publish(Info("Camel", classOf[Camel], String.format("Started CamelContext %s for ActorSystem %s", context.getName, system.name)))
    this
  }

  /**
   * Stops camel and underlying camel context and template.
   * Only the creator of Camel should shut it down.
   * There is no need to stop Camel instance, which you get from the CamelExtension, as its lifecycle is bound to the actor system.
   *
   * @see akka.camel.DefaultCamel#start()
   */
  def shutdown() {
    try context.stop() finally safe(template.stop())
    //TODO use proper akka logging
    system.eventStream.publish(Info("Camel", classOf[Camel], String.format("Stopped CamelContext %s for ActorSystem %s", context.getName, system.name)))
  }
}

object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider {

  /**
   * Creates new instance of Camel and makes sure it gets stopped when actor system is shutdown.
   */
  def createExtension(system: ActorSystemImpl) = {
    val camel = new DefaultCamel(system).start;
    system.registerOnTermination(camel.shutdown())
    camel
  }

  def lookup() = CamelExtension
}

/**
 * Watches the end of life of <code>Producer</code>s.
 * Removes a <code>Producer</code> from the <code>ProducerRegistry</code> when it is <code>Terminated</code>,
 * which in turn stops the <code>SendProcessor</code>.
 */
private[camel] class ProducerWatcher(registry: ProducerRegistry) extends Actor {
  override def receive = {
    case RegisterProducer(actorRef) ⇒ {
      context.watch(actorRef)
    }
    case Terminated(actorRef) ⇒ {
      registry.remove(actorRef)
    }
  }
}

private[camel] case class RegisterProducer(actorRef: ActorRef)

/**
 * Manages the Camel objects for <code>Producer</code>s.
 * Every <code>Producer</code> needs an <code>Endpoint</code> and a <code>SendProcessor</code>
 * to produce messages over an <code>Exchange</code>.
 */
private[camel] trait ProducerRegistry {
  this: Camel ⇒
  private val camelObjects = new ConcurrentHashMap[ActorRef, (Endpoint, SendProcessor)]()
  private val watcher = system.actorOf(Props(new ProducerWatcher(this)))

  private def registerWatch(actorRef: ActorRef) {
    watcher ! RegisterProducer(actorRef)
  }

  /**
   * removes <code>Endpoint</code> and <code>SendProcessor</code> and stops the SendProcessor
   */
  private[camel] def remove(actorRef: ActorRef): Unit = {
    // Terminated cannot be sent before the actor is created in the processing of system messages.
    val existing = camelObjects.get(actorRef)
    if (camelObjects.remove(actorRef, existing)) {
      try {
        existing._2.stop()
        system.eventStream.publish(EndpointDeActivated(actorRef))
      } catch {
        case e ⇒ system.eventStream.publish(EndpointFailedToDeActivate(actorRef, e))
      }
    }
  }

  /**
   * Creates <code>Endpoint</code> and <code>SendProcessor</code> and associates the actorRef to these.
   * @param actorRef the actorRef of the <code>Producer</code> actor.
   * @param endpointUri the endpoint Uri of the producer
   * @return <code>Endpoint</code> and <code>SendProcessor</code> registered for the actorRef
   */
  private[camel] def registerProducer(actorRef: ActorRef, endpointUri: String): (Endpoint, SendProcessor) = {
    try {
      val endpoint = context.getEndpoint(endpointUri)
      val processor = new SendProcessor(endpoint)

      val prev = camelObjects.putIfAbsent(actorRef, (endpoint, processor))
      if (prev != null) {
        prev
      } else {
        processor.start()
        system.eventStream.publish(EndpointActivated(actorRef))
        registerWatch(actorRef)
        (endpoint, processor)
      }
    } catch {
      case e ⇒ {
        system.eventStream.publish(EndpointFailedToActivate(actorRef, e))
        // can't return null to the producer actor, so blow up actor in initialization.
        throw e
      }
    }
  }
}

/**
 * Manages consumer registration. Consumers call registerConsumer method to register themselves  when they get created.
 * ActorEndpoint uses it to lookup an actor by its path.
 */
private[camel] trait ConsumerRegistry { this: Activation ⇒
  def system: ActorSystem
  def context: CamelContext

  private[this] lazy val idempotentRegistry = system.actorOf(Props(new IdempotentCamelConsumerRegistry(context)))

  private[camel] def registerConsumer(endpointUri: String, consumer: Consumer, activationTimeout: Duration) = {
    idempotentRegistry ! RegisterConsumer(endpointUri, consumer.self, consumer)
    awaitActivation(consumer.self, activationTimeout)
  }

}
