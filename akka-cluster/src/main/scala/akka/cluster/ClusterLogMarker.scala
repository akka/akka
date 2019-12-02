/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Address
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.event.LogMarker

@ApiMayChange
object ClusterLogMarker {

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] object Properties {
    val MemberStatus = "akkaMemberStatus"
  }

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
   * Marker "akkaClusterLeaderDetained" of log event when leader can't perform its duties.
   */
  val leaderDetained: LogMarker =
    LogMarker("akkaClusterLeaderDetained")

  /**
   * Marker "akkaClusterLeaderAllowed" of log event when leader can perform its duties again.
   */
  val leaderAllowed: LogMarker =
    LogMarker("akkaClusterLeaderAllowed")

  /**
   * Marker "akkaMemberChanged" of log event when a member's status is changed by the leader.
   * @param node The address of the node that is changed. Included as property "akkaRemoteAddress"
   *             and "akkaRemoteAddressUid".
   * @param status New member status. Included as property "akkaMemberStatus".
   */
  def memberChanged(node: UniqueAddress, status: MemberStatus): LogMarker =
    LogMarker(
      "akkaMemberChanged",
      Map(
        LogMarker.Properties.RemoteAddress -> node.address,
        LogMarker.Properties.RemoteAddressUid -> node.longUid,
        Properties.MemberStatus -> status))

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
