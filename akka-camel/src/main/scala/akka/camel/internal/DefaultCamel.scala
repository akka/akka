package akka.camel.internal

import akka.actor.ActorSystem
import component.{DurationTypeConverter, ActorComponent}
import org.apache.camel.CamelContext
import org.apache.camel.impl.DefaultCamelContext
import akka.util.Duration
import scala.Predef._
import akka.event.Logging
import akka.camel.Camel


/**
 * Creates an instance of Camel subsystem.
 *
 * @param system is used to create internal actors needed by camel instance.
 * Camel doesn't maintain the lifecycle of this actorSystem. It has to be shut down by the user.
 * In typical scenario, when camel is used with akka extension, it is natural that camel reuses the actor system it extends.
 * Also by not creating extra internal actor system we are conserving resources.
 */
private[camel] class DefaultCamel(val system: ActorSystem) extends Camel {
  private[camel] implicit val log = Logging(system, "Camel")

  lazy val context: CamelContext = {
    val ctx = new DefaultCamelContext
    ctx.setName(system.name);
    ctx.setStreamCaching(true)
    ctx.addComponent("actor", new ActorComponent(this))
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
    Try(template.start()) otherwise context.stop()
    log.debug("Started CamelContext[{}] for ActorSystem[{}]", context.getName, system.name)
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
    try context.stop() finally Try.safe(template.stop())
    log.debug("Stopped CamelContext[{}] for ActorSystem[{}]", context.getName, system.name)
  }
}
