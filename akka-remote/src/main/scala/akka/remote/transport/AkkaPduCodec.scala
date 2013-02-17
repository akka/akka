/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.transport

import akka.AkkaException
import akka.actor.{ AddressFromURIString, InternalActorRef, Address, ActorRef }
import akka.remote.RemoteProtocol._
import akka.remote.transport.AkkaPduCodec._
import akka.remote.{ RemoteActorRefProvider, RemoteProtocol }
import akka.util.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.{ ByteString ⇒ PByteString }

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class PduCodecException(msg: String, cause: Throwable) extends AkkaException(msg, cause)

/**
 * INTERNAL API
 *
 * Companion object of the [[akka.remote.transport.AkkaPduCodec]] trait. Contains the representation case classes
 * of decoded Akka Protocol Data Units (PDUs).
 */
private[remote] object AkkaPduCodec {

  /**
   * Trait that represents decoded Akka PDUs (Protocol Data Units)
   */
  sealed trait AkkaPdu

  case class Associate(cookie: Option[String], origin: Address) extends AkkaPdu
  case object Disassociate extends AkkaPdu
  case object Heartbeat extends AkkaPdu
  case class Payload(bytes: ByteString) extends AkkaPdu

  case class Message(recipient: InternalActorRef,
                     recipientAddress: Address,
                     serializedMessage: MessageProtocol,
                     senderOption: Option[ActorRef])
}

/**
 * INTERNAL API
 *
 * A Codec that is able to convert Akka PDUs (Protocol Data Units) from and to [[akka.util.ByteString]]s.
 */
private[remote] trait AkkaPduCodec {

  /**
   * Returns an [[akka.remote.transport.AkkaPduCodec.AkkaPdu]] instance that represents the PDU contained in the raw
   * ByteString.
   * @param raw
   *   Encoded raw byte representation of an Akka PDU
   * @return
   *   Case class representation of the decoded PDU that can be used in a match statement
   */
  def decodePdu(raw: ByteString): AkkaPdu

  /**
   * Takes an [[akka.remote.transport.AkkaPduCodec.AkkaPdu]] representation of an Akka PDU and returns its encoded
   * form as a [[akka.util.ByteString]].
   *
   * For the same effect the constructXXX methods might be called directly, taking method parameters instead of the
   * [[akka.remote.transport.AkkaPduCodec.AkkaPdu]] case classes.
   *
   * @param pdu
   *   The Akka Protocol Data Unit to be encoded
   * @return
   *   Encoded form as raw bytes
   */
  def encodePdu(pdu: AkkaPdu): ByteString = pdu match {
    case Associate(cookie, origin) ⇒ constructAssociate(cookie, origin)
    case Payload(bytes)            ⇒ constructPayload(bytes)
    case Disassociate              ⇒ constructDisassociate
    case Heartbeat                 ⇒ constructHeartbeat
  }

  def constructPayload(payload: ByteString): ByteString

  def constructAssociate(cookie: Option[String], origin: Address): ByteString

  def constructDisassociate: ByteString

  def constructHeartbeat: ByteString

  def decodeMessage(raw: ByteString, provider: RemoteActorRefProvider, localAddress: Address): Message

  def constructMessage(
    localAddress: Address,
    recipient: ActorRef,
    serializedMessage: MessageProtocol,
    senderOption: Option[ActorRef]): ByteString
}

/**
 * INTERNAL API
 */
private[remote] object AkkaPduProtobufCodec extends AkkaPduCodec {

  override def constructMessage(
    localAddress: Address,
    recipient: ActorRef,
    serializedMessage: MessageProtocol,
    senderOption: Option[ActorRef]): ByteString = {

    val messageBuilder = RemoteMessageProtocol.newBuilder

    messageBuilder.setRecipient(serializeActorRef(recipient.path.address, recipient))
    senderOption foreach { ref ⇒ messageBuilder.setSender(serializeActorRef(localAddress, ref)) }
    messageBuilder.setMessage(serializedMessage)

    ByteString(messageBuilder.build.toByteArray)
  }

  override def constructPayload(payload: ByteString): ByteString =
    ByteString(AkkaRemoteProtocol.newBuilder().setPayload(PByteString.copyFrom(payload.asByteBuffer)).build.toByteArray)

  override def constructAssociate(cookie: Option[String], origin: Address): ByteString =
    constructControlMessagePdu(RemoteProtocol.CommandType.CONNECT, cookie, Some(origin))

  override val constructDisassociate: ByteString =
    constructControlMessagePdu(RemoteProtocol.CommandType.SHUTDOWN, None, None)

  override val constructHeartbeat: ByteString =
    constructControlMessagePdu(RemoteProtocol.CommandType.HEARTBEAT, None, None)

  override def decodePdu(raw: ByteString): AkkaPdu = {
    try {
      val pdu = AkkaRemoteProtocol.parseFrom(raw.toArray)
      if (pdu.hasPayload) Payload(ByteString(pdu.getPayload.asReadOnlyByteBuffer()))
      else if (pdu.hasInstruction) decodeControlPdu(pdu.getInstruction)
      else throw new PduCodecException("Error decoding Akka PDU: Neither message nor control message were contained", null)
    } catch {
      case e: InvalidProtocolBufferException ⇒ throw new PduCodecException("Decoding PDU failed.", e)
    }
  }

  override def decodeMessage(
    raw: ByteString,
    provider: RemoteActorRefProvider,
    localAddress: Address): Message = {
    val msgPdu = RemoteMessageProtocol.parseFrom(raw.toArray)
    Message(
      recipient = provider.actorForWithLocalAddress(provider.rootGuardian, msgPdu.getRecipient.getPath, localAddress),
      recipientAddress = AddressFromURIString(msgPdu.getRecipient.getPath),
      serializedMessage = msgPdu.getMessage,
      senderOption = if (!msgPdu.hasSender) None
      else Some(provider.actorForWithLocalAddress(provider.rootGuardian, msgPdu.getSender.getPath, localAddress)))
  }

  private def decodeControlPdu(controlPdu: RemoteControlProtocol): AkkaPdu = {
    val cookie = if (controlPdu.hasCookie) Some(controlPdu.getCookie) else None

    controlPdu.getCommandType match {
      case CommandType.CONNECT if controlPdu.hasOrigin ⇒ Associate(cookie, decodeAddress(controlPdu.getOrigin))
      case CommandType.SHUTDOWN ⇒ Disassociate
      case CommandType.HEARTBEAT ⇒ Heartbeat
      case _ ⇒ throw new PduCodecException("Decoding of control PDU failed: format invalid", null)
    }
  }

  private def decodeAddress(encodedAddress: AddressProtocol): Address =
    Address(encodedAddress.getProtocol, encodedAddress.getSystem, encodedAddress.getHostname, encodedAddress.getPort)

  private def constructControlMessagePdu(
    code: RemoteProtocol.CommandType,
    cookie: Option[String],
    origin: Option[Address]): ByteString = {

    val controlMessageBuilder = RemoteControlProtocol.newBuilder()

    controlMessageBuilder.setCommandType(code)
    cookie foreach controlMessageBuilder.setCookie
    for (originAddress ← origin; serialized ← serializeAddress(originAddress))
      controlMessageBuilder.setOrigin(serialized)

    ByteString(AkkaRemoteProtocol.newBuilder().setInstruction(controlMessageBuilder.build).build.toByteArray)
  }

  private def serializeActorRef(defaultAddress: Address, ref: ActorRef): ActorRefProtocol = {
    ActorRefProtocol.newBuilder.setPath(
      if (ref.path.address.host.isDefined) ref.path.toString else ref.path.toStringWithAddress(defaultAddress)).build()
  }

  private def serializeAddress(address: Address): Option[AddressProtocol] = {
    for (host ← address.host; port ← address.port) yield AddressProtocol.newBuilder
      .setHostname(host)
      .setPort(port)
      .setSystem(address.system)
      .setProtocol(address.protocol)
      .build()
  }

}
