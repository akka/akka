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
// for editing these in IntelliJ, open module settings, change JDK dependency to 11 for only this module

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
@Category(Array("Akka", "Remoting", "Aeron")) @Label("Media driver started")
final case class TransportMediaDriverStarted(directoryName: String) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Transport started")
final case class TransportStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron")) @Label("Aeron error log started")
final case class TransportAeronErrorLogStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Task runner started")
final case class TransportTaskRunnerStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Unique address set")
final case class TransportUniqueAddressSet(_uniqueAddress: UniqueAddress) extends Event {
  val uniqueAddress = _uniqueAddress.toString()
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Materializer started")
final case class TransportMaterializerStarted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Startup finished")
final case class TransportStartupFinished() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Kill switch pulled")
final case class TransportKillSwitchPulled() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Stopped")
final case class TransportStopped() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron")) @Label("Aeron log task stopped")
final case class TransportAeronErrorLogTaskStopped() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Media file deleted")
final case class TransportMediaFileDeleted() extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Send queue overflow")
final case class TransportSendQueueOverflow(queueIndex: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Stop idle outbound")
final case class TransportStopIdleOutbound(_remoteAddress: Address, queueIndex: Int) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Quarantined")
final case class TransportQuarantined(_remoteAddress: Address, uid: Long) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Remove quarantined")
final case class TransportRemoveQuarantined(_remoteAddress: Address) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Restart outbound")
final case class TransportRestartOutbound(_remoteAddress: Address, streamName: String) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Transport")) @Label("Restart inbound")
final case class TransportRestartInbound(_remoteAddress: UniqueAddress, streamName: String) extends Event {
  val remoteAddress = _remoteAddress.toString()
}

// aeron sink events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Started")
final case class AeronSinkStarted(channel: String, streamId: Int) extends Event {}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Task runner removed")
final case class AeronSinkTaskRunnerRemoved(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Publication closed")
final case class AeronSinkPublicationClosed(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Publication closed unexpectedly")
final case class AeronSinkPublicationClosedUnexpectedly(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Stopped")
final case class AeronSinkStopped(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Envelope grabbed")
final case class AeronSinkEnvelopeGrabbed(@DataAmount() lastMessageSize: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Envelope offered")
final case class AeronSinkEnvelopeOffered(@DataAmount() lastMessageSize: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Gave up envelope")
final case class AeronSinkGaveUpEnvelope(cause: String) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Delegate to task runner")
final case class AeronSinkDelegateToTaskRunner(countBeforeDelegate: Long) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Sink")) @Label("Return from task runner")
final case class AeronSinkReturnFromTaskRunner(@Timespan(Timespan.NANOSECONDS) nanosSinceTaskStartTime: Long)
    extends Event

// aeron source events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Started")
final case class AeronSourceStarted(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Stopped")
final case class AeronSourceStopped(channel: String, streamId: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Received")
final case class AeronSourceReceived(@DataAmount() size: Int) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Delegate to task runner")
final case class AeronSourceDelegateToTaskRunner(countBeforeDelegate: Long) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Aeron", "Source")) @Label("Return from task runner")
final case class AeronSourceReturnFromTaskRunner(@Timespan(Timespan.NANOSECONDS) nanosSinceTaskStartTime: Long)
    extends Event

// compression events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Compression")) @Label("ActorRef advertisement")
final case class CompressionActorRefAdvertisement(uid: Long) extends Event

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Compression")) @Label("ClassManifest advertisement")
final case class CompressionClassManifestAdvertisement(uid: Long) extends Event

// tcp outbound events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Outbound")) @Label("Connected")
final case class TcpOutboundConnected(_remoteAddress: Address, streamName: String) extends Event {
  val remoteAddress = _remoteAddress.toString
}

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Outbound")) @Label("Sent")
final case class TcpOutboundSent(@DataAmount() size: Int) extends Event

// tcp inbound events

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Bound")
final case class TcpInboundBound(bindHost: String, _address: InetSocketAddress) extends Event {
  val address = JFREventUtils.stringOf(_address)
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Unbound")
final case class TcpInboundUnbound(_localAddress: UniqueAddress) extends Event {
  val localAddress = _localAddress.toString()
}

/**
 * INTERNAL API
 */
@InternalApi
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Connected")
final case class TcpInboundConnected(_remoteAddress: InetSocketAddress) extends Event {
  val remoteAddress = JFREventUtils.stringOf(_remoteAddress)
}

/**
 * INTERNAL API
 */
@InternalApi
@Enabled(false) // hi frequency event
@StackTrace(false)
@Category(Array("Akka", "Remoting", "Tcp", "Inbound")) @Label("Received")
final case class TcpInboundReceived(@DataAmount() size: Int) extends Event
