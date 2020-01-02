/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Address
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.event.LogMarker

/**
 * This is public with the purpose to document the used markers and properties of log events.
 * No guarantee that it will remain binary compatible, but the marker names and properties
 * are considered public API and will not be changed without notice.
 */
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
   * Marker "akkaClusterLeaderIncapacitated" of log event when leader can't perform its duties.
   * Typically because there are unreachable nodes that have not been downed.
   */
  val leaderIncapacitated: LogMarker =
    LogMarker("akkaClusterLeaderIncapacitated")

  /**
   * Marker "akkaClusterLeaderRestored" of log event when leader can perform its duties again.
   */
  val leaderRestored: LogMarker =
    LogMarker("akkaClusterLeaderRestored")

  /**
   * Marker "akkaJoinFailed" of log event when node couldn't join seed nodes.
   */
  val joinFailed: LogMarker =
    LogMarker("akkaJoinFailed")

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
