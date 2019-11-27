/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.Address
import akka.annotation.ApiMayChange
import akka.event.LogMarker

@ApiMayChange
object RemoteLogMarker {

  /**
   * Marker "akkaFailureDetectorGrowing" of log event when failure detector heartbeat interval
   * is growing too large.
   *
   * @param remoteAddress The address of the node that the failure detector is monitoring. Included as property "akkaRemoteAddress".
   */
  def failureDetectorGrowing(remoteAddress: String): LogMarker =
    LogMarker("akkaFailureDetectorGrowing", Map(LogMarker.Properties.RemoteAddress -> remoteAddress))

  /**
   * Marker "akkaQuarantine" of log event when a node is quarantined.
   *
   * @param remoteAddress The address of the node that is quarantined. Included as property "akkaRemoteAddress".
   * @param remoteAddressUid The address of the node that is quarantined. Included as property "akkaRemoteAddressUid".
   */
  def quarantine(remoteAddress: Address, remoteAddressUid: Option[Long]): LogMarker =
    LogMarker(
      "akkaQuarantine",
      Map(
        LogMarker.Properties.RemoteAddress -> remoteAddress,
        LogMarker.Properties.RemoteAddressUid -> remoteAddressUid.getOrElse("")))

  /**
   * Marker "akkaConnected" of log event when outbound connection is established.
   *
   * @param remoteAddress The address of the connected node. Included as property "akkaRemoteAddress".
   * @param remoteAddressUid The address of the connected node. Included as property "akkaRemoteAddressUid".
   */
  def connected(remoteAddress: Address, remoteAddressUid: Option[Long]): LogMarker =
    LogMarker(
      "akkaConnected",
      Map(
        LogMarker.Properties.RemoteAddress -> remoteAddress,
        LogMarker.Properties.RemoteAddressUid -> remoteAddressUid.getOrElse("")))

  /**
   * Marker "akkaDisconnected" of log event when outbound connection is closed.
   *
   * @param remoteAddress The address of the disconnected node. Included as property "akkaRemoteAddress".
   * @param remoteAddressUid The address of the disconnected node. Included as property "akkaRemoteAddressUid".
   */
  def disconnected(remoteAddress: Address, remoteAddressUid: Option[Long]): LogMarker =
    LogMarker(
      "akkaDisconnected",
      Map(
        LogMarker.Properties.RemoteAddress -> remoteAddress,
        LogMarker.Properties.RemoteAddressUid -> remoteAddressUid.getOrElse("")))
}
