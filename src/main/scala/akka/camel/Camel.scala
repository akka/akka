package akka.camel

import component.{BlockingOrNotTypeConverter, DurationTypeConverter, ActorComponent, Path}
import java.util.concurrent.TimeoutException
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.{ProducerTemplate, CamelContext}
import collection.mutable.HashMap
import akka.event.Logging.Info
import akka.util.{Timeout, Duration}
import akka.util.ErrorUtils._
import akka.actor.{ExtensionIdProvider, ActorSystemImpl, ExtensionId, Extension, Props, ActorSystem, ActorRef}
import akka.dispatch.Future

trait Camel extends ConsumerRegistry with Extension with Activation{
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


  def registerConsumer(route: String, consumer: Consumer,  activationTimeout : Duration) = {
    consumerPublisher ! RegisterConsumer(route, consumer)
    awaitActivation(consumer.self, activationTimeout)
  }

  def findConsumer(path: Path) : Option[ActorRef] = Option(actorSystem.actorFor(path.value))
}

trait Activation{ this : Camel =>
  import akka.dispatch.Await

  val actorSystem : ActorSystem
  private[camel] val activationListener = actorSystem.actorOf(Props[ActivationTracker])

  //TODO we need better name for this
  def activationAwaitableFor(actor: ActorRef, timeout: Duration): Future[Unit] = {
    (activationListener ?(AwaitActivation(actor), Timeout(timeout))).map[Unit]{
      case EndpointActivated(_) => {}
      case EndpointFailedToActivate(_, cause) => throw cause
    }
  }

  /**
   * Awaits for actor to be activated.
   */

  def awaitActivation(actor: ActorRef, timeout: Duration){
    try{
      Await.result(activationAwaitableFor(actor, timeout), timeout)
    }catch {
      case e: TimeoutException => throw new ActivationTimeoutException
    }
  }

  //TODO we need better name for this
  def deactivationAwaitableFor(actor: ActorRef, timeout: Duration): Future[Unit] = {
    (activationListener ?(AwaitDeActivation(actor), Timeout(timeout))).map[Unit]{
      case EndpointDeActivated(_) => {}
      case EndpointFailedToDeActivate(_, cause) => throw cause
    }
  }

  def awaitDeactivation(actor: ActorRef, timeout: Duration) {
    try{
      Await.result(deactivationAwaitableFor(actor, timeout), timeout)
    }catch {
      case e: TimeoutException => throw new DeActivationTimeoutException
    }
  }



}