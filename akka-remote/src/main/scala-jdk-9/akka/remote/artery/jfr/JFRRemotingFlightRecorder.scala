/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.jfr

import java.net.InetSocketAddress

import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.remote.UniqueAddress
import akka.remote.artery.RemotingFlightRecorder

final class JFRRemotingFlightRecorder(system: ExtendedActorSystem) extends RemotingFlightRecorder {
  override def transportMediaDriverStarted(directoryName: String): Unit =
    TransportMediaDriverStarted(directoryName).commit()

  override def transportStarted(): Unit =
    TransportStarted().commit()

  override def transportAeronErrorLogStarted(): Unit =
    TransportAeronErrorLogStarted().commit()

  override def transportTaskRunnerStarted(): Unit =
    TransportTaskRunnerStarted().commit()

  override def transportUniqueAddressSet(uniqueAddress: UniqueAddress): Unit =
    TransportUniqueAddressSet(uniqueAddress).commit()

  override def transportMaterializerStarted(): Unit =
    TransportMaterializerStarted().commit()

  override def transportStartupFinished(): Unit =
    TransportStartupFinished().commit()

  override def transportKillSwitchPulled(): Unit =
    TransportKillSwitchPulled().commit()

  override def transportStopped(): Unit =
    TransportStopped().commit()

  override def transportAeronErrorLogTaskStopped(): Unit =
    TransportAeronErrorLogTaskStopped().commit()

  override def transportMediaFileDeleted(): Unit =
    TransportMediaFileDeleted().commit()

  override def transportSendQueueOverflow(queueIndex: Int): Unit =
    TransportSendQueueOverflow(queueIndex).commit()

  override def transportStopIdleOutbound(remoteAddress: Address, queueIndex: Int): Unit =
    TransportStopIdleOutbound(remoteAddress, queueIndex).commit()

  override def transportQuarantined(remoteAddress: Address, uid: Long): Unit =
    TransportQuarantined(remoteAddress, uid).commit()

  override def transportRemoveQuarantined(remoteAddress: Address): Unit =
    TransportRemoveQuarantined(remoteAddress).commit()

  override def transportRestartOutbound(remoteAddress: Address, streamName: String): Unit =
    TransportRestartOutbound(remoteAddress, streamName).commit()

  override def transportRestartInbound(remoteAddress: UniqueAddress, streamName: String): Unit =
    TransportRestartInbound(remoteAddress, streamName).commit()

  override def aeronSinkStarted(channel: String, streamId: Int, channelMetadata: Array[Byte]): Unit =
    AeronSinkStarted(channel, streamId).commit()

  override def aeronSinkTaskRunnerRemoved(channel: String, streamId: Int, channelMetadata: Array[Byte]): Unit =
    AeronSinkTaskRunnerRemoved(channel, streamId).commit()

  override def aeronSinkPublicationClosed(channel: String, streamId: Int, channelMetadata: Array[Byte]): Unit =
    AeronSinkPublicationClosed(channel, streamId).commit()

  override def aeronSinkPublicationClosedUnexpectedly(
      channel: String,
      streamId: Int,
      channelMetadata: Array[Byte]): Unit =
    AeronSinkPublicationClosedUnexpectedly(channel, streamId).commit()

  override def aeronSinkStopped(channel: String, streamId: Int, channelMetadata: Array[Byte]): Unit =
    AeronSinkStopped(channel, streamId).commit()

  override def aeronSinkEnvelopeGrabbed(lastMessageSize: Int): Unit =
    AeronSinkEnvelopeGrabbed(lastMessageSize).commit()

  override def aeronSinkEnvelopeOffered(lastMessageSize: Int): Unit =
    AeronSinkEnvelopeOffered(lastMessageSize).commit()

  override def aeronSinkGaveUpEnvelope(cause: String): Unit =
    AeronSinkGaveUpEnvelope(cause).commit()

  override def aeronSinkDelegateToTaskRunner(countBeforeDelegate: Long): Unit =
    AeronSinkDelegateToTaskRunner(countBeforeDelegate).commit()

  override def aeronSinkReturnFromTaskRunner(nanosSinceTaskStartTime: Long): Unit =
    AeronSinkReturnFromTaskRunner(nanosSinceTaskStartTime).commit()

  override def aeronSourceStarted(channel: String, streamId: Int, channelMetadata: Array[Byte]): Unit =
    AeronSourceStarted(channel, streamId).commit()

  override def aeronSourceStopped(channel: String, streamId: Int, channelMetadata: Array[Byte]): Unit =
    AeronSourceStopped(channel, streamId).commit()

  override def aeronSourceReceived(size: Int): Unit =
    AeronSourceReceived(size).commit()

  override def aeronSourceDelegateToTaskRunner(countBeforeDelegate: Long): Unit =
    AeronSourceDelegateToTaskRunner(countBeforeDelegate).commit()

  override def aeronSourceReturnFromTaskRunner(nanosSinceTaskStartTime: Long): Unit =
    AeronSourceReturnFromTaskRunner(nanosSinceTaskStartTime).commit()

  override def compressionActorRefAdvertisement(uid: Long): Unit =
    CompressionActorRefAdvertisement(uid).commit()

  override def compressionClassManifestAdvertisement(uid: Long): Unit =
    CompressionClassManifestAdvertisement(uid).commit()

  override def tcpOutboundConnected(remoteAddress: Address, streamName: String): Unit =
    TcpOutboundConnected(remoteAddress, streamName).commit()

  override def tcpOutboundSent(size: Int): Unit =
    TcpOutboundSent(size).commit()

  override def tcpInboundBound(bindHost: String, address: InetSocketAddress): Unit =
    TcpInboundBound(bindHost, address).commit()

  override def tcpInboundUnbound(localAddress: UniqueAddress): Unit =
    TcpInboundUnbound(localAddress).commit()

  override def tcpInboundConnected(remoteAddress: InetSocketAddress): Unit =
    TcpInboundConnected(remoteAddress).commit()

  override def tcpInboundReceived(size: Int): Unit =
    TcpInboundReceived(size).commit()
}
