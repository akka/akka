package akka.camel

import component.{BlockingOrNotTypeConverter, DurationTypeConverter, ActorComponent, Path}
import migration.Migration
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import akka.actor.{Props, ActorSystem, Actor, ActorRef}
import java.lang.{IllegalStateException, String}
import org.apache.camel.{ProducerTemplate, CamelContext}
import akka.util.{Timeout, Duration}
import akka.util.duration._

trait Camel{
  def context : CamelContext
  def template : ProducerTemplate
  def addRoutes(routeBuilder: RouteBuilder)
  def stopRoute(routeId: String)
  def start : Camel
  def stop : Unit
}

//TODO: Get rid of the singleton!
object Camel{

  class CamelInstance extends Camel with ConsumerRegistry{
    val context = {
      val ctx = new DefaultCamelContext
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
      context start;
      template start;
      Migration.EventHandler.Info("Started Camel instance "+this)
      this
    }

    override def stop {
      super.stop;
      context.stop();
      template.stop()
      Migration.EventHandler.Info("Stoped Camel instance "+this)
    }
  }

  private[this] var _instance : Option[Camel with ConsumerRegistry] = None
  def instance = _instance.getOrElse(throw new IllegalStateException("Camel not started"))

  def start = _instance match{
    case None => {_instance = Some(new CamelInstance().start); instance}
    case _ => throw new IllegalStateException("Camel alerady started!")
  }
  def stop {instance.stop; _instance = None}

  def context = instance.context
  def template = instance.template
}

/**
 * Manages consumer registration. Consumers call registerConsumer method to register themselves  when they get created.
 * ActorEndpoint uses it to lookup an actor by its path.
 */
trait ConsumerRegistry{
  self:Camel =>
  val actorSystem  = ActorSystem("Camel")
  private[camel] val consumerPublisher = actorSystem.actorOf(Props(new ConsumerPublisher(this)))
  //TODO: save some kittens and use less blocking collection
  val consumers = synchronized(scala.collection.mutable.HashMap[Path, ActorRef]())

  def registerConsumer(route: String, consumer: Consumer with Actor) = {
    consumers.put(Path(consumer.self.path.toString), consumer.self)
    consumerPublisher.ask(ConsumerActorRegistered(route, consumer.self, consumer), Timeout(1 minute)).onSuccess{
      case EndpointActivated => consumer.self ! EndpointActivated
    }
  }

  def unregisterConsumer(consumer: Consumer with Actor) = {
    val path = Path(consumer.self.path.toString)
    consumers.remove(path)
    consumerPublisher.ask(ConsumerActorUnregistered(path, consumer.self), Timeout(1 minute)).onSuccess{
      case EndpointDeActivated => consumer.postDeactivation //has to be synchronous as the actor is already dead
    }
  }


  def findConsumer(path: Path) : Option[ActorRef] = consumers.get(path)

  override def stop {
    actorSystem.shutdown()
  }
}
