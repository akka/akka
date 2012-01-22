package akka.camel

import internal.component.{CommunicationStyleTypeConverter, DurationTypeConverter, ActorComponent}
import internal._
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.{ProducerTemplate, CamelContext}
import akka.event.Logging.Info
import akka.util.Duration
import akka.actor.{ExtensionIdProvider, ActorSystemImpl, ExtensionId, Extension, Props, ActorSystem}

trait Camel extends ConsumerRegistry with Extension with Activation{
  def context : CamelContext
  def template : ProducerTemplate

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
class DefaultCamel(val system : ActorSystem) extends Camel{
  lazy val context : CamelContext = {
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
    try template.start() catch { case e => context.stop(); throw e }
    //TODO use proper akka logging
    system.eventStream.publish(Info("Camel", classOf[Camel],String.format("Started CamelContext %s for ActorSystem %s",context.getName, system.name)))
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
    try context.stop() finally template.stop()
    //TODO use proper akka logging
    system.eventStream.publish(Info("Camel",classOf[Camel], String.format("Stopped CamelContext %s for ActorSystem %s",context.getName, system.name)))
  }
}

object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider{

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
 * Manages consumer registration. Consumers call registerConsumer method to register themselves  when they get created.
 * ActorEndpoint uses it to lookup an actor by its path.
 */
private[camel] trait ConsumerRegistry{ this:Activation =>
  def system : ActorSystem
  def context : CamelContext

  private[this] lazy val idempotentRegistry = system.actorOf(Props(new IdempotentCamelConsumerRegistry(context)))


  private[camel] def registerConsumer(endpointUri: String, consumer: Consumer,  activationTimeout : Duration) = {
    idempotentRegistry ! RegisterConsumer(endpointUri, consumer.self, consumer)
    awaitActivation(consumer.self, activationTimeout)
  }

}
