package akka.remote.actmote

import akka.actor.ActorRef

/**
 * Created with IntelliJ IDEA.
 * User: Varga Endre Sándor
 * Date: 2012.08.20.
 * Time: 10:47
 * To change this template use File | Settings | File Templates.
 */
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

trait TransportConnector {
  import akka.actor.Address

  def responsibleActor: ActorRef
  def responsibleActor_=(actor: ActorRef): Unit
  def address: Address
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

