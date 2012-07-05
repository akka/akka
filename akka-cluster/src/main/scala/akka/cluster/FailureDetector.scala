/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Address

/**
 * Interface for Akka failure detectors.
 */
trait FailureDetector {

  /**
   * Returns true if the connection is considered to be up and healthy and returns false otherwise.
   */
  def isAvailable(connection: Address): Boolean

  /**
   * Records a heartbeat for a connection.
   */
  def heartbeat(connection: Address): Unit

  /**
   * Removes the heartbeat management for a connection.
   */
  def remove(connection: Address): Unit

  /**
   * Removes all connections and starts over.
   */
  def reset(): Unit
}
