package akka.camel

import internal.component.{BlockingOrNotTypeConverter, DurationTypeConverter, ActorComponent, Path}
import internal._
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.{ProducerTemplate, CamelContext}
import collection.mutable.HashMap
import akka.event.Logging.Info
import akka.util.Duration
import akka.util.ErrorUtils._
import akka.actor.{ExtensionIdProvider, ActorSystemImpl, ExtensionId, Extension, Props, ActorSystem, ActorRef}

trait Camel extends ConsumerRegistry with Extension with Activation{
  def context : CamelContext
  def template : ProducerTemplate
  def start : Camel
  def stop : Unit

  /**
   * Refers back to the associated ActorSystem
   */
  def system: ActorSystem
}

/**
 * Creates an instance of Camel subsystem.
 *
 * @param actorSystem is used to create internal actors needed by camel instance.
 * Camel doesn't maintain the lifecycle of this actorSystem. It has to be shut down by the user.
 * In typical scenario, when camel is used as akka extension it is natural that camel reuses the  actor system it extends.
 * Also by not creating extra internal actor system we are conserving resources. //TODO: (maybe it's premature optimisation?)
 */
class DefaultCamel(val actorSystem : ActorSystem) extends Camel{
  val context = {
    val ctx = new DefaultCamelContext
    ctx.setName(actorSystem.name);
    ctx.setStreamCaching(true)
    ctx.addComponent("actor", new ActorComponent(this))
    ctx.getTypeConverterRegistry.addTypeConverter(classOf[BlockingOrNot], classOf[String], BlockingOrNotTypeConverter)
    ctx.getTypeConverterRegistry.addTypeConverter(classOf[Duration], classOf[String], DurationTypeConverter)
    ctx
  }

  val template = context.createProducerTemplate()
  def system = actorSystem
  def start = {
    context.start
    template.start
    actorSystem.eventStream.publish(Info("Camel",String.format("Started CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
    this
  }

  override def stop {
    tryAll(
      context.stop(),
      template.stop()
    )
    actorSystem.eventStream.publish(Info("Camel",String.format("Stopped CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
  }
}

object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider{
  val overrides = synchronized(new HashMap[ActorSystem, Camel])
  /**
   * If you need to start Camel context outside of extension you can use this method
   * to tell the actor system which camel instance it should use.
   */
  def setCamelFor(system: ActorSystem, camel: Camel) { overrides(system) = camel } //TODO: putIfAbsent maybe?

  def createExtension(system: ActorSystemImpl) = {

    def useOverride(system: ActorSystemImpl) = {
      system.registerOnTermination(overrides.remove(system))
      overrides(system)
    }

    def createNew(system: ActorSystemImpl) = {
      val camel = new DefaultCamel(system).start;
      system.registerOnTermination(camel.stop)
      camel
    }

    if(overrides.contains(system)) useOverride(system) else createNew(system)
  }
  def lookup() = CamelExtension
}

/**
 * Manages consumer registration. Consumers call registerConsumer method to register themselves  when they get created.
 * ActorEndpoint uses it to lookup an actor by its path.
 */
trait ConsumerRegistry{
  self:Camel =>
  val actorSystem : ActorSystem
  private[camel] val consumerPublisher = actorSystem.actorOf(Props(new ConsumerPublisher(this)))


  private[camel] def registerConsumer(route: String, consumer: Consumer,  activationTimeout : Duration) = {
    consumerPublisher ! RegisterConsumer(route, consumer)
    awaitActivation(consumer.self, activationTimeout)
  }
  // this might have problems with val initialization, since I also needed it for producers, I added the system to Camel.
  def findConsumer(path: Path) : Option[ActorRef] = Option(actorSystem.actorFor(path.value))
}
