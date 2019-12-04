/*
 * Copyright (C) extends Event 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.jfr

import java.net.InetSocketAddress

import akka.actor.Address
import akka.annotation.InternalApi
import akka.remote.UniqueAddress
import jdk.jfr.StackTrace
import jdk.jfr.Category
import jdk.jfr.Label
import jdk.jfr.Event
import jdk.jfr.DataAmount
import jdk.jfr.Enabled
import jdk.jfr.Timespan

// requires jdk9+ to compile

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object JFREventUtils {

  def stringOf(address: InetSocketAddress): String =
    s"${address.getHostString}:${address.getPort}"

}

// transport events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Media driver started")
case class TransportMediaDriverStarted(directoryName: String) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Transport started")
case class TransportStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Aeron error log started")
case class TransportAeronErrorLogStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Task runner started")
case class TransportTaskRunnerStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Unique address set")
case class TransportUniqueAddressSet(_uniqueAddress: UniqueAddress) extends Event {
  val uniqueAddress = _uniqueAddress.toString()
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Materializer started")
case class TransportMaterializerStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Startup finished")
case class TransportStartupFinished() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Kill switch pulled")
case class TransportKillSwitchPulled() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Stopped")
case class TransportStopped() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Aeron log task stopped")
case class TransportAeronErrorLogTaskStopped() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Media file deleted")
case class TransportMediaFileDeleted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Send queue overflow")
case class TransportSendQueueOverflow(queueIndex: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Stop idle outbound")
case class TransportStopIdleOutbound(_remoteAddress: Address, queueIndex: Int) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Quarantined")
case class TransportQuarantined(_remoteAddress: Address, uid: Long) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Remove quarantined")
case class TransportRemoveQuarantined(_remoteAddress: Address) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Restart outbound")
case class TransportRestartOutbound(_remoteAddress: Address, streamName: String) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Restart inbound")
case class TransportRestartInbound(_remoteAddress: UniqueAddress, streamName: String) extends Event {
  val remoteAddress = _remoteAddress.toString()
}

// aeron sink events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Started")
case class AeronSinkStarted(channel: String, streamId: Int) extends Event {}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Task runner removed")
case class AeronSinkTaskRunnerRemoved(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Publication closed")
case class AeronSinkPublicationClosed(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Publication closed unexpectedly")
case class AeronSinkPublicationClosedUnexpectedly(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Stopped")
case class AeronSinkStopped(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Envelope grabbed")
case class AeronSinkEnvelopeGrabbed(@DataAmount() lastMessageSize: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Envelope offered")
case class AeronSinkEnvelopeOffered(@DataAmount() lastMessageSize: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Gave up envelope")
case class AeronSinkGaveUpEnvelope(cause: String) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Delegate to task runner")
case class AeronSinkDelegateToTaskRunner(countBeforeDelegate: Long) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Return from task runner")
case class AeronSinkReturnFromTaskRunner(@Timespan(Timespan.NANOSECONDS) nanosSinceTaskStartTime: Long) extends Event

// aeron source events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Started")
case class AeronSourceStarted(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Stopped")
case class AeronSourceStopped(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Received")
case class AeronSourceReceived(@DataAmount() size: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Delegate to task runner")
case class AeronSourceDelegateToTaskRunner(countBeforeDelegate: Long) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Return from task runner")
case class AeronSourceReturnFromTaskRunner(@Timespan(Timespan.NANOSECONDS) nanosSinceTaskStartTime: Long) extends Event

// compression events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Compression")) @Label("ActorRef advertisement")
case class CompressionActorRefAdvertisement(uid: Long) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Compression")) @Label("ClassManifest advertisement")
case class CompressionClassManifestAdvertisement(uid: Long) extends Event

// tcp outbound events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Outbound")) @Label("Connected")
case class TcpOutboundConnected(_remoteAddress: Address, streamName: String) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Outbound")) @Label("Sent")
case class TcpOutboundSent(@DataAmount() size: Int) extends Event

// tcp inbound events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Bound")
case class TcpInboundBound(bindHost: String, _address: InetSocketAddress) extends Event {
  val address = JFREventUtils.stringOf(_address)
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Unbound")
case class TcpInboundUnbound(_localAddress: UniqueAddress) extends Event {
  val localAddress = _localAddress.toString()
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Connected")
case class TcpInboundConnected(_remoteAddress: InetSocketAddress) extends Event {
  val remoteAddress = JFREventUtils.stringOf(_remoteAddress)
}

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Received")
case class TcpInboundReceived(@DataAmount() size: Int) extends Event
