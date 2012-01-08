package akka.camel

import component.{BlockingOrNotTypeConverter, DurationTypeConverter, ActorComponent, Path}
import migration.Migration
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import java.lang.String
import org.apache.camel.{ProducerTemplate, CamelContext}
import akka.util.{Timeout, Duration}
import akka.util.duration._
import akka.actor.{ExtensionIdProvider, ActorSystemImpl, ExtensionId, Extension, Props, ActorSystem, Actor, ActorRef}
import collection.mutable.HashMap
import akka.event.EventStream
import akka.event.Logging.Info

trait Camel extends ConsumerRegistry with MessageFactory with Extension{
  def context : CamelContext
  def template : ProducerTemplate
  def addRoutes(routeBuilder: RouteBuilder) :Unit
  def stopRoute(routeId: String) : Unit
  def start : Camel
  def stop : Unit
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

  def addRoutes(routeBuilder: RouteBuilder) {context addRoutes routeBuilder}
  def stopRoute(routeId: String) = context.stopRoute(routeId)

  def start = {
    try {
      context.start
    } finally {
      template.start
    }
    actorSystem.eventStream.publish(Info("Camel",String.format("Started CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
    this
  }

  override def stop {
    try {
      context.stop()
    } finally {
      template.stop()
    }
    actorSystem.eventStream.publish(Info("Camel",String.format("Stopped CamelContext %s for ActorSystem %s",context.getName, actorSystem.name)))
  }
}

object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider{
  //TODO not threadsafe
  val overrides = new HashMap[ActorSystem, Camel]
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
  val actorSystem : ActorSystem //TODO: maybe sharing of an actor system for internal purposes is not so good idea? What if user forgets to shut it down?
  private[camel] val consumerPublisher = actorSystem.actorOf(Props(new ConsumerPublisher(this)))


  def registerConsumer(route: String, consumer: Consumer with Actor) = {
    consumerPublisher.tell(ConsumerActorRegistered(route, consumer.self, consumer), consumer.self)
  }

  def unregisterConsumer(consumer: Consumer with Actor) = {
    val path = Path(consumer.self.path.toString)
    consumerPublisher.ask(ConsumerActorUnregistered(path, consumer.self), Timeout(1 minute)).onSuccess{
      case EndpointDeActivated => consumer.postDeactivation //has to be synchronous as the actor is already dead
    }
  }

  def findConsumer(path: Path) : Option[ActorRef] = Option(actorSystem.actorFor(path.value))
}
