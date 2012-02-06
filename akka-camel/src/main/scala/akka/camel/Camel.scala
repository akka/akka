/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal._
import akka.actor._
import org.apache.camel.{ ProducerTemplate, CamelContext }

/**
 * Camel trait encapsulates the underlying camel machinery.
 * It can be used to access
 */
trait Camel extends ConsumerRegistry with ProducerRegistry with Extension with Activation {
  /**
   * Underlying camel context.
   *
   * It can be used to configure camel manually, i.e. when the user wants to add new routes or endpoints,
   * i.e. <pre>camel.context.addRoutes(...)</pre>
   *
   * @see [[org.apache.camel.CamelContext]]
   */
  def context: CamelContext

  /**
   * Producer template.
   * @see [[org.apache.camel.ProducerTemplate]]
   */
  def template: ProducerTemplate

  /**
   * Associated `ActorSystem`.
   *
   * <p>It can be used to start producers, consumers or any other actors which need to interact with camel,
   * for example:
   * {{{
   * val system = ActorSystem("test")
   * system.actorOf(Props[SysOutConsumer])
   *
   * class SysOutConsumer extends Consumer {
   * def endpointUri = "file://data/input/CamelConsumer"
   *
   * protected def receive = {
   * case msg: Message ⇒ {
   * printf("Received '%s'\\n", msg.bodyAs[String])
   * }
   * }
   * }
   * }}}
   * '''Note:''' This actor system is responsible for stopping the underlying camel instance.
   *
   * @see [[akka.camel.CamelExtension]]
   */
  def system: ActorSystem
}

/**
 * This class can be used to get hold of an instance of Camel class bound to the actor system.
 * <p>For example:
 * {{{
 * val system = ActorSystem("some system")
 * val camel = CamelExtension(system)
 * camel.context.addRoutes(...)
 * }}}
 *
 * @see akka.actor.ExtensionId
 * @see akka.actor.ExtensionIdProvider
 *
 */
object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider {

  /**
   * Creates new instance of Camel and makes sure it gets stopped when the actor system is shutdown.
   */
  def createExtension(system: ActorSystemImpl) = {
    val camel = new DefaultCamel(system).start;
    system.registerOnTermination(camel.shutdown())
    camel
  }

  def lookup() = CamelExtension
}