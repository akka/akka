/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.actor.Address
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

/**
 * Base trait for all failure detector strategies.
 */
trait FailureDetectorStrategy {

  /**
   * Get or create the FailureDetector to be used in the cluster node.
   * To be defined by subclass.
   */
  def failureDetector: FailureDetector

  /**
   * Marks a node as available in the failure detector.
   * To be defined by subclass.
   */
  def markNodeAsAvailable(address: Address): Unit

  /**
   * Marks a node as unavailable in the failure detector.
   * To be defined by subclass.
   */
  def markNodeAsUnavailable(address: Address): Unit
}

/**
 * Defines a FailureDetectorPuppet-based FailureDetectorStrategy.
 */
trait FailureDetectorPuppetStrategy extends FailureDetectorStrategy { self: MultiNodeSpec ⇒

  /**
   * The puppet instance. Separated from 'failureDetector' field so we don't have to cast when using the puppet specific methods.
   */
  private val puppet = new FailureDetectorPuppet(system)

  override def failureDetector: FailureDetector = puppet

  override def markNodeAsAvailable(address: Address): Unit = puppet markNodeAsAvailable address

  override def markNodeAsUnavailable(address: Address): Unit = puppet markNodeAsUnavailable address
}

/**
 * Defines a AccrualFailureDetector-based FailureDetectorStrategy.
 */
trait AccrualFailureDetectorStrategy extends FailureDetectorStrategy { self: MultiNodeSpec ⇒

  override val failureDetector: FailureDetector = new AccrualFailureDetector(system, new ClusterSettings(system.settings.config, system.name))

  override def markNodeAsAvailable(address: Address): Unit = ()

  override def markNodeAsUnavailable(address: Address): Unit = ()
}
