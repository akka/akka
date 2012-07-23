/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal._
import akka.actor._
import org.apache.camel.{ ProducerTemplate, CamelContext }
import com.typesafe.config.Config
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit._

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

  /**
   * The settings for the CamelExtension
   */
  def settings: CamelSettings
}

/**
 * Settings for the Camel Extension
 * @param config the config
 */
class CamelSettings(val config: Config) {
  /**
   * Configured setting for how long the actor should wait for activation before it fails.
   */
  final val activationTimeout: Duration = Duration(config.getMilliseconds("akka.camel.consumer.activationTimeout"), MILLISECONDS)
  /**
   * Configured setting, When endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   */
  final val replyTimeout: Duration = Duration(config.getMilliseconds("akka.camel.consumer.replyTimeout"), MILLISECONDS)
  /**
   * Configured setting which determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   */
  final val autoAck: Boolean = config.getBoolean("akka.camel.consumer.autoAck")
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