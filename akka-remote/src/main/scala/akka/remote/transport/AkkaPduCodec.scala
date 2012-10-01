package akka.remote.transport

import akka.AkkaException
import akka.actor.{AddressFromURIString, InternalActorRef, Address, ActorRef}
import akka.remote.RemoteProtocol._
import akka.remote.transport.AkkaPduCodec._
import akka.remote.{RemoteActorRefProvider, RemoteProtocol}
import akka.util.ByteString
import com.google.protobuf.InvalidProtocolBufferException

class PduCodecException(msg: String, cause: Throwable) extends AkkaException(msg, cause)

object AkkaPduCodec {
  sealed trait AkkaPdu

  case class Associate(cookie: Option[String], origin: Address) extends AkkaPdu
  case object Disassociate extends AkkaPdu
  case object Heartbeat extends AkkaPdu
  case class Message(recipient: InternalActorRef,
                     recipientAddress: Address,
                     serializedMessage: MessageProtocol,
                     sender: Option[ActorRef]) extends AkkaPdu
}

trait AkkaPduCodec {

  def constructMessagePdu(
    localAddress: Address,
    recipient: ActorRef,
    serializedMessage: MessageProtocol,
    senderOption: Option[ActorRef]): ByteString

  def constructAssociate(cookie: Option[String], origin: Address): ByteString

  def constructDisassociate: ByteString

  def constructHeartbeat: ByteString

  def decodePdu(raw: ByteString, provider: RemoteActorRefProvider): AkkaPdu // Effective enough?

}

object AkkaPduProtobufCodec extends AkkaPduCodec {

  override def constructMessagePdu(
     localAddress: Address,
     recipient: ActorRef,
     serializedMessage: MessageProtocol,
     senderOption: Option[ActorRef]): ByteString = {

    val messageBuilder = RemoteMessageProtocol.newBuilder

    messageBuilder.setRecipient(serializeActorRef(localAddress, recipient))
    senderOption foreach { ref => messageBuilder.setSender(serializeActorRef(localAddress, ref)) }
    messageBuilder.setMessage(serializedMessage)

    akkaRemoteProtocolToByteString(AkkaRemoteProtocol.newBuilder().setMessage(messageBuilder.build).build)
  }

  // TODO: names should be following the terminology in design doc
  override def constructAssociate(cookie: Option[String], origin: Address): ByteString =
    constructControlMessagePdu(RemoteProtocol.CommandType.CONNECT, cookie, Some(origin))

  override val constructDisassociate: ByteString =
    constructControlMessagePdu(RemoteProtocol.CommandType.SHUTDOWN, None, None)

  override val constructHeartbeat: ByteString =
    constructControlMessagePdu(RemoteProtocol.CommandType.HEARTBEAT, None, None)

  override def decodePdu(raw: ByteString, provider: RemoteActorRefProvider): AkkaPdu = {
    try {
      val pdu = AkkaRemoteProtocol.parseFrom(raw.toArray)

      if (pdu.hasMessage) {
        decodeMessage(pdu.getMessage, provider)
      } else if (pdu.hasInstruction) {
        decodeControlPdu(pdu.getInstruction)
      } else {
        throw new PduCodecException("Error decoding Akka PDU: Neither message nor control message were contained", null)
      }
    } catch {
      case e: InvalidProtocolBufferException => throw new PduCodecException("Decoding PDU failed.", e)
    }
  }

  private def decodeMessage(msgPdu: RemoteMessageProtocol, provider: RemoteActorRefProvider): Message = {
    Message(
      recipient = provider.actorFor(provider.rootGuardian, msgPdu.getRecipient.getPath),
      recipientAddress = AddressFromURIString(msgPdu.getRecipient.getPath),
      serializedMessage = msgPdu.getMessage,
      sender = if (msgPdu.hasSender) Some(provider.actorFor(provider.rootGuardian, msgPdu.getSender.getPath)) else None
    )
  }

  private def decodeControlPdu(controlPdu: RemoteControlProtocol): AkkaPdu = {
    val cookie = if (controlPdu.hasCookie) Some(controlPdu.getCookie) else None

    controlPdu.getCommandType match {
      case CommandType.CONNECT if controlPdu.hasOrigin => Associate(cookie, decodeAddress(controlPdu.getOrigin))
      case CommandType.SHUTDOWN => Disassociate
      case CommandType.HEARTBEAT => Heartbeat
    }
  }

  private def decodeAddress(encodedAddress: AddressProtocol): Address =
    Address(encodedAddress.getProtocol, encodedAddress.getSystem, encodedAddress.getHostname, encodedAddress.getPort)

  //TODO: Set a maximum length for secure cookies
  private def constructControlMessagePdu(
    code: RemoteProtocol.CommandType,
    cookie: Option[String],
    origin: Option[Address]): ByteString = {

    val controlMessageBuilder = RemoteControlProtocol.newBuilder()

    controlMessageBuilder.setCommandType(code)
    cookie foreach { controlMessageBuilder.setCookie(_) }
    (for (originAddress <- origin; serialized <- serializeAddress(originAddress)) yield serialized) foreach {
      controlMessageBuilder.setOrigin(_)
    }

    akkaRemoteProtocolToByteString(AkkaRemoteProtocol.newBuilder().setInstruction(controlMessageBuilder.build).build)
  }

  private def akkaRemoteProtocolToByteString(pdu: AkkaRemoteProtocol): ByteString = ByteString(pdu.toByteArray)

  private def serializeActorRef(defaultAddress: Address, ref: ActorRef): ActorRefProtocol =
    ActorRefProtocol.newBuilder.setPath(fullActorRefString(defaultAddress, ref)).build()

  private def serializeAddress(address: Address): Option[AddressProtocol] = {
    for (host <- address.host; port <- address.port) yield {
      AddressProtocol.newBuilder
        .setHostname(host)
        .setPort(port)
        .setSystem(address.system)
        .setProtocol(address.protocol)
        .build()
    }
  }

  private def fullActorRefString(defaultAddress: Address, ref: ActorRef): String = if (ref.path.address.host.isDefined) {
    ref.path.toString
  } else {
    ref.path.toStringWithAddress(defaultAddress)
  }

}
