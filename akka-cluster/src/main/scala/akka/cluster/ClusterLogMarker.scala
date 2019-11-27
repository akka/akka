/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Address
import akka.annotation.ApiMayChange
import akka.event.LogMarker

@ApiMayChange
object ClusterLogMarker {

  /**
   * Marker "akkaUnreachable" of log event when a node is marked as unreachable based no failure detector observation.
   * @param node The address of the node that is marked as unreachable. Included as property "akkaRemoteAddress".
   */
  def unreachable(node: Address): LogMarker =
    LogMarker("akkaUnreachable", Map(LogMarker.Properties.RemoteAddress -> node))

  /**
   * Marker "akkaReachable" of log event when a node is marked as reachable again based no failure detector observation.
   * @param node The address of the node that is marked as reachable. Included as property "akkaRemoteAddress".
   */
  def reachable(node: Address): LogMarker =
    LogMarker("akkaReachable", Map(LogMarker.Properties.RemoteAddress -> node))

  /**
   * Marker "akkaHeartbeatStarvation" of log event when scheduled heartbeat was delayed.
   */
  val heartbeatStarvation: LogMarker =
    LogMarker("akkaHeartbeatStarvation")

  /**
   * Marker "akkaClusterSingletonStarted" of log event when Cluster Singleton
   * instance has started.
   */
  val singletonStarted: LogMarker =
    LogMarker("akkaClusterSingletonStarted")

  /**
   * Marker "akkaClusterSingletonTerminated" of log event when Cluster Singleton
   * instance has terminated.
   */
  val singletonTerminated: LogMarker =
    LogMarker("akkaClusterSingletonTerminated")

}
