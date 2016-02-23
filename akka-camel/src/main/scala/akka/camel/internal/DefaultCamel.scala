/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.camel.internal

import akka.camel.internal.component.{ DurationTypeConverter, ActorComponent }
import org.apache.camel.impl.DefaultCamelContext
import scala.Predef._
import akka.event.Logging
import akka.camel.{ CamelSettings, Camel }
import akka.camel.internal.ActivationProtocol._
import scala.util.control.NonFatal
import scala.concurrent.duration._
import org.apache.camel.ProducerTemplate
import scala.concurrent.{ Future, ExecutionContext }
import akka.util.Timeout
import akka.pattern.ask
import akka.actor.{ ExtendedActorSystem, ActorRef, Props }

/**
 * INTERNAL API
 * Creates an instance of the Camel subsystem.
 *
 * @param system is used to create internal actors needed by camel instance.
 * Camel doesn't maintain the lifecycle of this actor system. The actor system has to be shut down by the user.
 * In the typical scenario, when camel is used with akka extension, it is natural that camel reuses the actor system it extends.
 * Also by not creating extra internal actor system we are conserving resources.
 */
private[camel] class DefaultCamel(val system: ExtendedActorSystem) extends Camel {
  val supervisor = system.actorOf(Props[CamelSupervisor], "camel-supervisor")
  private[camel] implicit val log = Logging(system, getClass.getName)

  lazy val context: DefaultCamelContext = {
    val ctx = settings.ContextProvider.getContext(system)
    if (!settings.JmxStatistics) ctx.disableJMX()
    ctx.setName(system.name)
    ctx.setStreamCaching(settings.StreamingCache)
    ctx.addComponent("akka", new ActorComponent(this, system))
    ctx.getTypeConverterRegistry.addTypeConverter(classOf[FiniteDuration], classOf[String], DurationTypeConverter)
    ctx
  }

  val settings = new CamelSettings(system.settings.config, system.dynamicAccess)

  lazy val template: ProducerTemplate = context.createProducerTemplate()

  /**
   * Starts camel and underlying camel context and template.
   * Only the creator of Camel should start and stop it.
   * @see akka.camel.internal.DefaultCamel#shutdown
   */
  def start(): this.type = {
    context.start()
    try template.start() catch { case NonFatal(e) ⇒ context.stop(); throw e }
    log.debug("Started CamelContext[{}] for ActorSystem[{}]", context.getName, system.name)
    this
  }

  /**
   * Stops camel and underlying camel context and template.
   * Only the creator of Camel should shut it down.
   * There is no need to stop Camel instance, which you get from the CamelExtension, as its lifecycle is bound to the actor system.
   *
   * @see akka.camel.internal.DefaultCamel#start
   */
  def shutdown(): Unit = {
    try context.stop() finally {
      try template.stop() catch { case NonFatal(e) ⇒ log.debug("Swallowing non-fatal exception [{}] on stopping Camel producer template", e) }
    }
    log.debug("Stopped CamelContext[{}] for ActorSystem[{}]", context.getName, system.name)
  }

  /**
   * Produces a Future with the specified endpoint that will be completed when the endpoint has been activated,
   * or if it times out, which will happen after the specified Timeout.
   *
   * @param endpoint the endpoint to be activated
   * @param timeout the timeout for the Future
   */
  def activationFutureFor(endpoint: ActorRef)(implicit timeout: Timeout, executor: ExecutionContext): Future[ActorRef] =

    (supervisor.ask(AwaitActivation(endpoint))(timeout)).map[ActorRef]({
      case EndpointActivated(`endpoint`)               ⇒ endpoint
      case EndpointFailedToActivate(`endpoint`, cause) ⇒ throw cause
    })

  /**
   * Produces a Future which will be completed when the given endpoint has been deactivated or
   * or if it times out, which will happen after the specified Timeout.
   *
   * @param endpoint the endpoint to be deactivated
   * @param timeout the timeout of the Future
   */
  def deactivationFutureFor(endpoint: ActorRef)(implicit timeout: Timeout, executor: ExecutionContext): Future[ActorRef] =
    (supervisor.ask(AwaitDeActivation(endpoint))(timeout)).map[ActorRef]({
      case EndpointDeActivated(`endpoint`)               ⇒ endpoint
      case EndpointFailedToDeActivate(`endpoint`, cause) ⇒ throw cause
    })
}
