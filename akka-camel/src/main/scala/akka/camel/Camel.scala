/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal._
import akka.actor._
import org.apache.camel.{ ProducerTemplate, CamelContext }

/**
 * Camel trait encapsulates the underlying camel machinery.
 * '''Note:''' `CamelContext` and `ProducerTemplate` are stopped when the associated actor system is shut down.
 * This trait can be obtained through the [[akka.camel.CamelExtension]] object.
 */
trait Camel extends ConsumerRegistry with ProducerRegistry with Extension with Activation {
  /**
   * Underlying camel context.
   *
   * It can be used to configure camel manually, i.e. when the user wants to add new routes or endpoints,
   * i.e. {{{camel.context.addRoutes(...)}}}
   *
   * @see [[org.apache.camel.CamelContext]]
   */
  def context: CamelContext

  /**
   * The Camel ProducerTemplate.
   * @see [[org.apache.camel.ProducerTemplate]]
   */
  def template: ProducerTemplate

}

/**
 * This class can be used to get hold of an instance of the Camel class bound to the actor system.
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
   * Creates a new instance of Camel and makes sure it gets stopped when the actor system is shutdown.
   */
  override def createExtension(system: ExtendedActorSystem): Camel = {
    val camel = new DefaultCamel(system).start
    system.registerOnTermination(camel.shutdown())
    camel
  }

  override def lookup(): ExtensionId[Camel] = CamelExtension

  override def get(system: ActorSystem): Camel = super.get(system)
}