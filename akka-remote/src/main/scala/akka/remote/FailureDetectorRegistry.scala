/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor.{ ActorContext, ActorSystem, ExtendedActorSystem }
import com.typesafe.config.Config
import akka.event.EventStream
import akka.ConfigurationException

/**
 * Interface for a registry of Akka failure detectors. New resources are implicitly registered when heartbeat is first
 * called with the resource given as parameter.
 *
 * @tparam A
 *   The type of the key that identifies a resource to be monitored by a failure detector
 */
trait FailureDetectorRegistry[A] {

  /**
   * Returns true if the resource is considered to be up and healthy and returns false otherwise.
   * For unregistered resources it returns true.
   */
  def isAvailable(resource: A): Boolean

  /**
   * Returns true if the failure detector has received any heartbeats and started monitoring
   * of the resource.
   */
  def isMonitoring(resource: A): Boolean

  /**
   * Records a heartbeat for a resource. If the resource is not yet registered (i.e. this is the first heartbeat) then
   * it is automatially registered.
   */
  def heartbeat(resource: A): Unit

  /**
   * Removes the heartbeat management for a resource.
   */
  def remove(resource: A): Unit

  /**
   * Removes all resources and any associated failure detector state.
   */
  def reset(): Unit
}

object FailureDetectorLoader {

  def load(fqcn: String, config: Config, system: ActorSystem): FailureDetector = {
    system.asInstanceOf[ExtendedActorSystem].dynamicAccess.createInstanceFor[FailureDetector](
      fqcn, List(
        classOf[Config] -> config,
        classOf[EventStream] -> system.eventStream)).recover({
        case e ⇒ throw new ConfigurationException(
          s"Could not create custom failure detector [$fqcn] due to: ${e.toString}", e)
      }).get
  }

  def apply(fqcn: String, config: Config)(implicit ctx: ActorContext) = load(fqcn, config, ctx.system)

}
