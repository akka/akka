/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.event.Logging.LogLevel
import akka.event.{ Logging, LoggingAdapter }
import akka.actor.{ ActorSystem, Address }

import scala.runtime.AbstractFunction2

@SerialVersionUID(1L)
sealed trait RemotingLifecycleEvent extends Serializable {
  def logLevel: Logging.LogLevel
}

@SerialVersionUID(1L)
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
final case class AssociatedEvent(
  localAddress:  Address,
  remoteAddress: Address,
  inbound:       Boolean)
  extends AssociationEvent {

  protected override def eventName: String = "Associated"
  override def logLevel: Logging.LogLevel = Logging.DebugLevel

}

@SerialVersionUID(1L)
final case class DisassociatedEvent(
  localAddress:  Address,
  remoteAddress: Address,
  inbound:       Boolean)
  extends AssociationEvent {
  protected override def eventName: String = "Disassociated"
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
}

@SerialVersionUID(1L)
final case class AssociationErrorEvent(
  cause:         Throwable,
  localAddress:  Address,
  remoteAddress: Address,
  inbound:       Boolean,
  logLevel:      Logging.LogLevel) extends AssociationEvent {
  protected override def eventName: String = "AssociationError"
  override def toString: String = s"${super.toString}: Error [${cause.getMessage}] [${Logging.stackTraceFor(cause)}]"
  def getCause: Throwable = cause
}

@SerialVersionUID(1L)
final case class RemotingListenEvent(listenAddresses: Set[Address]) extends RemotingLifecycleEvent {
  def getListenAddresses: java.util.Set[Address] =
    scala.collection.JavaConverters.setAsJavaSetConverter(listenAddresses).asJava
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "Remoting now listens on addresses: " + listenAddresses.mkString("[", ", ", "]")
}

@SerialVersionUID(1L)
case object RemotingShutdownEvent extends RemotingLifecycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override val toString: String = "Remoting shut down"
}

@SerialVersionUID(1L)
final case class RemotingErrorEvent(cause: Throwable) extends RemotingLifecycleEvent {
  def getCause: Throwable = cause
  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = s"Remoting error: [${cause.getMessage}] [${Logging.stackTraceFor(cause)}]"
}

// For binary compatibility
object QuarantinedEvent extends AbstractFunction2[Address, Int, QuarantinedEvent] {

  @deprecated("Use long uid apply")
  def apply(address: Address, uid: Int) = new QuarantinedEvent(address, uid)
}

@SerialVersionUID(1L)
final case class QuarantinedEvent(address: Address, longUid: Long) extends RemotingLifecycleEvent {

  override def logLevel: Logging.LogLevel = Logging.WarningLevel
  override val toString: String =
    s"Association to [$address] having UID [$longUid] is irrecoverably failed. UID is now quarantined and all " +
      "messages to this UID will be delivered to dead letters. Remote actorsystem must be restarted to recover " +
      "from this situation."

  // For binary compatibility

  @deprecated("Use long uid constructor")
  def this(address: Address, uid: Int) = this(address, uid.toLong)

  @deprecated("Use long uid")
  def uid: Int = longUid.toInt

  @deprecated("Use long uid copy method")
  def copy(address: Address = address, uid: Int = uid) = new QuarantinedEvent(address, uid)
}

@SerialVersionUID(1L)
final case class ThisActorSystemQuarantinedEvent(localAddress: Address, remoteAddress: Address) extends RemotingLifecycleEvent {
  override def logLevel: LogLevel = Logging.WarningLevel
  override val toString: String = s"The remote system ${remoteAddress} has quarantined this system ${localAddress}."
}

/**
 * INTERNAL API
 */
private[remote] class EventPublisher(system: ActorSystem, log: LoggingAdapter, logLevel: Logging.LogLevel) {
  def notifyListeners(message: RemotingLifecycleEvent): Unit = {
    system.eventStream.publish(message)
    if (message.logLevel <= logLevel) log.log(message.logLevel, "{}", message)
  }
}
