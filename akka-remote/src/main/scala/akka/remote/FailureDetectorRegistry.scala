/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

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
   */
  def isAvailable(resource: A): Boolean

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
