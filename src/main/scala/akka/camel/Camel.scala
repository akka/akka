package akka.camel

import internal.component.{BlockingOrNotTypeConverter, DurationTypeConverter, ActorComponent, Path}
import internal._
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.{ProducerTemplate, CamelContext}
import akka.event.Logging.Info
import akka.util.Duration
import akka.util.ErrorUtils._
import akka.actor.{ExtensionIdProvider, ActorSystemImpl, ExtensionId, Extension, Props, ActorSystem, ActorRef}
import java.util.concurrent.ConcurrentHashMap

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
 * @param actorSystem is used to create internal actors needed by camel instance.
 * Camel doesn't maintain the lifecycle of this actorSystem. It has to be shut down by the user.
 * In typical scenario, when camel is used with akka extension, it is natural that camel reuses the  actor system it extends.
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

  /**
   * Starts camel and underlying camel context and template.
   * Only the creator of Camel should start and stop it.
   * @see akka.camel.DefaultCamel#stop()
   */
  //TODO consider starting Camel during initialization to avoid lifecycle issues. This would require checking if we are not limiting context configuration after it's started.
  def start = {
    context.start()
    template.start()
    actorSystem.eventStream.publish(Info("Camel",String.format("Started CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
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
    tryAll(
      context.stop(),
      template.stop()
    )
    actorSystem.eventStream.publish(Info("Camel",String.format("Stopped CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
  }
}

object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider{
  private[this] val overrides = new ConcurrentHashMap[ActorSystem, Camel]

  /**
   * If you need to start Camel context outside of extension you can use this method
   * to tell the actor system which camel instance it should use.
   * The user is responsible for stopping such a Camel instance.
   * The override is only valid until the actor system is terminated.
   */
  def setCamelFor(system: ActorSystem, camel: Camel) { overrides.put(system, camel) }

  /**
   * Creates new instance of Camel and makes sure it gets stopped when actor system is shutdown.
   *
   * <br/><br/>When the user wants to use a specific Camel instance, they can set an override - see setCamelFor(ActorSystem, Camel)
   * In this case the user is responsible for stopping the Camel instance.
   *
   */
  def createExtension(system: ActorSystemImpl) = {

    def useOverride(system: ActorSystemImpl) = {
      val camel = overrides.get(system)
      system.registerOnTermination(overrides.remove(system))
      camel
    }

    def createNew(system: ActorSystemImpl) = {
      val camel = new DefaultCamel(system).start;
      system.registerOnTermination(camel.shutdown())
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
