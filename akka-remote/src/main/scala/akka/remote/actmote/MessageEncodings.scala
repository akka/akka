package akka.remote.actmote

import akka.remote.RemoteProtocol._
import akka.actor._
import akka.serialization.Serialization
import akka.remote.MessageSerializer

trait MessageEncodings {
  def address: Address
  def system: ExtendedActorSystem

  /**
   * Returns a newly created AkkaRemoteProtocol with the given message payload.
   */
  def createMessageSendEnvelope(rmp: RemoteMessageProtocol): AkkaRemoteProtocol =
    AkkaRemoteProtocol.newBuilder.setMessage(rmp).build

  /**
   * Returns a newly created AkkaRemoteProtocol with the given control payload.
   */
  def createControlEnvelope(rcp: RemoteControlProtocol): AkkaRemoteProtocol =
    AkkaRemoteProtocol.newBuilder.setInstruction(rcp).build

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol =
    ActorRefProtocol.newBuilder.setPath(actor.path.toStringWithAddress(address)).build

  /**
   * Returns a new RemoteMessageProtocol containing the serialized representation of the given parameters.
   */
  def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {
    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    Serialization.currentTransportAddress.withValue(address) {
      messageBuilder.setMessage(MessageSerializer.serialize(system, message.asInstanceOf[AnyRef]))
    }

    messageBuilder
  }
}
