package akka.remote

import akka.event.{ LoggingAdapter, Logging }
import akka.actor.{ ActorSystem, Address }
import scala.beans.BeanProperty
import java.util.{ Set ⇒ JSet }
import scala.collection.JavaConverters.setAsJavaSetConverter

sealed trait RemotingLifecycleEvent extends Serializable {
  def logLevel: Logging.LogLevel
}

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

final case class AssociatedEvent(
  localAddress: Address,
  remoteAddress: Address,
  inbound: Boolean)
  extends AssociationEvent {

  protected override val eventName: String = "Associated"
  override def logLevel: Logging.LogLevel = Logging.DebugLevel

}

final case class DisassociatedEvent(
  localAddress: Address,
  remoteAddress: Address,
  inbound: Boolean)
  extends AssociationEvent {
  protected override val eventName: String = "Disassociated"
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
}

final case class AssociationErrorEvent(
  cause: Throwable,
  localAddress: Address,
  remoteAddress: Address,
  inbound: Boolean) extends AssociationEvent {
  protected override val eventName: String = "AssociationError"
  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = s"${super.toString}: Error[${Logging.stackTraceFor(cause)}]"
  def getCause: Throwable = cause
}

final case class RemotingListenEvent(listenAddresses: Set[Address]) extends RemotingLifecycleEvent {
  def getListenAddresses: JSet[Address] = listenAddresses.asJava
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "Remoting now listens on addresses: " + listenAddresses.mkString("[", ", ", "]")
}

case object RemotingShutdownEvent extends RemotingLifecycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override val toString: String = "Remoting shut down"
}

final case class RemotingErrorEvent(@BeanProperty cause: Throwable) extends RemotingLifecycleEvent {
  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = s"Remoting error: [${Logging.stackTraceFor(cause)}]"
}

class EventPublisher(system: ActorSystem, log: LoggingAdapter, logEvents: Boolean) {
  def notifyListeners(message: RemotingLifecycleEvent): Unit = {
    system.eventStream.publish(message)
    if (logEvents) log.log(message.logLevel, "{}", message)
  }
}