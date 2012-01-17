package akka.camel

import internal.component.{BlockingOrNotTypeConverter, DurationTypeConverter, ActorComponent, Path}
import internal._
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.{ProducerTemplate, CamelContext}
import akka.event.Logging.Info
import akka.util.Duration
import akka.actor.{ExtensionIdProvider, ActorSystemImpl, ExtensionId, Extension, Props, ActorSystem, ActorRef}
import collection.mutable.{SynchronizedMap, HashMap}

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
 * Also by not creating extra internal actor system we are conserving resources.
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
    //TODO use proper akka logging
    actorSystem.eventStream.publish(Info("Camel", classOf[Camel],String.format("Started CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
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
    context.stop()
    template.stop()
    //TODO use proper akka logging
    actorSystem.eventStream.publish(Info("Camel",classOf[Camel], String.format("Stopped CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
  }
}

object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider{
  private[this] val overrides = new HashMap[ActorSystem, Camel] with SynchronizedMap[ActorSystem, Camel]     //had to revert to SyncMap as ConcurrentHasMap doesn't guarantee that writes will happen before reads

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

    def useOverride(system: ActorSystem) = {
      val camel = overrides(system)
      system.registerOnTermination(overrides.remove(system))
      camel
    }

    def createNew(system: ActorSystem) = {
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
private[camel] trait ConsumerRegistry{ this:Activation =>
  def actorSystem : ActorSystem
  def context : CamelContext

  private[this] lazy val idempotentRegistry = actorSystem.actorOf(Props(new IdempotentCamelConsumerRegistry(context)))


  private[camel] def registerConsumer(route: String, consumer: Consumer,  activationTimeout : Duration) = {
    idempotentRegistry ! RegisterConsumer(route, consumer.self, consumer)
    awaitActivation(consumer.self, activationTimeout)
  }
  private[camel] def findActor(path: Path) : Option[ActorRef] = {
    //TODO this is a bit hacky, maybe there is another way?
    val actorRef = actorSystem.actorFor(path.actorPath)
    actorRef.path.name match {
      case "deadLetters" => None
      case _ => Some(actorRef)
    }
  }
}
