package akka.remote.actmote

import akka.actor.{ ExtendedActorSystem, ActorRef }
import akka.remote.RemoteActorRefProvider

// TODO: have a better name
// TODO: Use futures instead of callbacks??

object TransportConnector {

  import akka.remote.RemoteMessage

  sealed trait ConnectorEvent
  case class MessageArrived(msg: RemoteMessage) extends ConnectorEvent
  case class IncomingConnection(handle: TransportConnectorHandle)
  case class ConnectionInitialized(handle: TransportConnectorHandle) extends ConnectorEvent
  case class ConnectionFailed(reason: Throwable) extends ConnectorEvent
  case class Disconnected(handle: TransportConnectorHandle) extends ConnectorEvent
}

abstract class TransportConnector(val system: ExtendedActorSystem, val provider: RemoteActorRefProvider) {

  import akka.actor.Address

  def responsibleActor: ActorRef
  def responsibleActor_=(actor: ActorRef): Unit
  def address: Address
  // TODO: Document that all retry logic MUST be in connect
  def connect(remote: Address, responsibleActorForConnection: ActorRef = responsibleActor): Unit
  def shutdown(): Unit
}

trait TransportConnectorHandle {

  import akka.actor.{ Address, ActorRef }
  import akka.remote.RemoteActorRef

  def responsibleActor: ActorRef
  def responsibleActor_=(actor: ActorRef): Unit
  def remoteAddress: Address
  def close(): Unit
  def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit
}

