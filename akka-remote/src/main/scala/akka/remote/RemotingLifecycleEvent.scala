/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.runtime.AbstractFunction2

import scala.annotation.nowarn

import akka.actor.{ ActorSystem, Address }
import akka.event.{ Logging, LoggingAdapter }
import akka.event.Logging.LogLevel

@nowarn("msg=@SerialVersionUID has no effect")
@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
sealed trait RemotingLifecycleEvent extends Serializable {
  def logLevel: Logging.LogLevel
}

@nowarn("msg=@SerialVersionUID has no effect")
@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
sealed trait AssociationEvent extends RemotingLifecycleEvent {
  def localAddress: Address
  def remoteAddress: Address
  def inbound: Boolean
  protected def eventName: String
  final def getRemoteAddress: Address = remoteAddress
  final def getLocalAddress: Address = localAddress
  final def isInbound: Boolean = inbound
  override def toString: String = s"$eventName [$localAddress]${if (inbound) " <- " else " -> "}[$remoteAddress]"
}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class AssociatedEvent(localAddress: Address, remoteAddress: Address, inbound: Boolean)
    extends AssociationEvent {

  protected override def eventName: String = "Associated"
  override def logLevel: Logging.LogLevel = Logging.DebugLevel

}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class DisassociatedEvent(localAddress: Address, remoteAddress: Address, inbound: Boolean)
    extends AssociationEvent {
  protected override def eventName: String = "Disassociated"
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class AssociationErrorEvent(
    cause: Throwable,
    localAddress: Address,
    remoteAddress: Address,
    inbound: Boolean,
    logLevel: Logging.LogLevel)
    extends AssociationEvent {
  protected override def eventName: String = "AssociationError"
  override def toString: String = s"${super.toString}: Error [${cause.getMessage}] [${Logging.stackTraceFor(cause)}]"
  def getCause: Throwable = cause
}

@SerialVersionUID(1L)
@nowarn("msg=deprecated")
final case class RemotingListenEvent(listenAddresses: Set[Address]) extends RemotingLifecycleEvent {
  def getListenAddresses: java.util.Set[Address] =
    scala.collection.JavaConverters.setAsJavaSetConverter(listenAddresses).asJava
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "Remoting now listens on addresses: " + listenAddresses.mkString("[", ", ", "]")
}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
case object RemotingShutdownEvent extends RemotingLifecycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override val toString: String = "Remoting shut down"
}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class RemotingErrorEvent(cause: Throwable) extends RemotingLifecycleEvent {
  def getCause: Throwable = cause
  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = s"Remoting error: [${cause.getMessage}] [${Logging.stackTraceFor(cause)}]"
}

// For binary compatibility
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
object QuarantinedEvent extends AbstractFunction2[Address, Int, QuarantinedEvent] {

  @deprecated("Use long uid apply", "2.4.x")
  def apply(address: Address, uid: Int) = new QuarantinedEvent(address, uid)
}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class QuarantinedEvent(address: Address, longUid: Long) extends RemotingLifecycleEvent {

  override def logLevel: Logging.LogLevel = Logging.WarningLevel
  override val toString: String =
    s"Association to [$address] having UID [$longUid] is irrecoverably failed. UID is now quarantined and all " +
    "messages to this UID will be delivered to dead letters. Remote ActorSystem must be restarted to recover " +
    "from this situation."

  // For binary compatibility

  @deprecated("Use long uid constructor", "2.4.x")
  def this(address: Address, uid: Int) = this(address, uid.toLong)

  @deprecated("Use long uid", "2.4.x")
  def uid: Int = longUid.toInt

}

/**
 * The `uniqueAddress` was quarantined but it was due to normal shutdown or cluster leaving/exiting.
 */
@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class GracefulShutdownQuarantinedEvent(uniqueAddress: UniqueAddress, reason: String)
    extends RemotingLifecycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override val toString: String =
    s"Association to [${uniqueAddress.address}] having UID [${uniqueAddress.uid}] has been stopped. All " +
    s"messages to this UID will be delivered to dead letters. Reason: $reason "
}

@SerialVersionUID(1L)
@deprecated("Classic remoting is deprecated, use Artery", "2.6.0")
final case class ThisActorSystemQuarantinedEvent(localAddress: Address, remoteAddress: Address)
    extends RemotingLifecycleEvent {
  override def logLevel: LogLevel = Logging.WarningLevel
  override val toString: String = s"The remote system ${remoteAddress} has quarantined this system ${localAddress}."
}

/**
 * INTERNAL API
 */
@nowarn("msg=deprecated")
private[remote] class EventPublisher(system: ActorSystem, log: LoggingAdapter, logLevel: Logging.LogLevel) {
  def notifyListeners(message: RemotingLifecycleEvent): Unit = {
    system.eventStream.publish(message)
    if (message.logLevel <= logLevel) log.log(message.logLevel, "{}", message)
  }
}
