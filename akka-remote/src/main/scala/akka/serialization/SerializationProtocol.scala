/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.actor._
import akka.actor.DeploymentConfig._
import akka.dispatch.Envelope
import akka.util.{ ReflectiveAccess, Duration }
import akka.event.EventHandler
import akka.remote._
import RemoteProtocol._
import akka.AkkaApplication

import scala.collection.immutable.Stack

import java.net.InetSocketAddress
import java.util.{ LinkedList, Collections }

import com.google.protobuf.ByteString

import com.eaio.uuid.UUID

class RemoteActorSerialization(remote: RemoteSupport) {

  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte]): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, None)

  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte], loader: ClassLoader): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, Some(loader))

  /**
   * Deserializes a RemoteActorRefProtocol Protocol Buffers (protobuf) Message into an RemoteActorRef instance.
   */
  private[akka] def fromProtobufToRemoteActorRef(protocol: RemoteActorRefProtocol, loader: Option[ClassLoader]): ActorRef = {
    remote.app.eventHandler.debug(this, "Deserializing RemoteActorRefProtocol to RemoteActorRef:\n %s".format(protocol))

    val ref = RemoteActorRef(
      remote,
      JavaSerializer.fromBinary(protocol.getInetSocketAddress.toByteArray, Some(classOf[InetSocketAddress]), loader).asInstanceOf[InetSocketAddress],
      protocol.getAddress,
      loader)

    remote.app.eventHandler.debug(this, "Newly deserialized RemoteActorRef has uuid: %s".format(ref.uuid))

    ref
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): RemoteActorRefProtocol = {
    val remoteAddress = actor match {
      case ar: RemoteActorRef ⇒
        ar.remoteAddress
      case ar: LocalActorRef ⇒
        remote.registerByUuid(ar)
        remote.app.defaultAddress
      case _ ⇒
        remote.app.defaultAddress
    }

    remote.app.eventHandler.debug(this, "Register serialized Actor [%s] as remote @ [%s]".format(actor.uuid, remoteAddress))

    RemoteActorRefProtocol.newBuilder
      .setInetSocketAddress(ByteString.copyFrom(JavaSerializer.toBinary(remoteAddress)))
      .setAddress(actor.address)
      .setTimeout(remote.app.AkkaConfig.ActorTimeoutMillis)
      .build
  }

  def createRemoteMessageProtocolBuilder(
    actorRef: Option[ActorRef],
    replyUuid: Either[Uuid, UuidProtocol],
    actorAddress: String,
    timeout: Long,
    message: Either[Throwable, Any],
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val uuidProtocol = replyUuid match {
      case Left(uid)       ⇒ UuidProtocol.newBuilder.setHigh(uid.getTime).setLow(uid.getClockSeqAndNode).build
      case Right(protocol) ⇒ protocol
    }

    val actorInfoBuilder = ActorInfoProtocol.newBuilder
      .setUuid(uuidProtocol)
      .setAddress(actorAddress)
      .setTimeout(timeout)

    val actorInfo = actorInfoBuilder.build
    val messageBuilder = RemoteMessageProtocol.newBuilder
      .setUuid({
        val messageUuid = newUuid
        UuidProtocol.newBuilder.setHigh(messageUuid.getTime).setLow(messageUuid.getClockSeqAndNode).build
      })
      .setActorInfo(actorInfo)
      .setOneWay(true)

    message match {
      case Right(message) ⇒
        messageBuilder.setMessage(MessageSerializer.serialize(remote.app, message.asInstanceOf[AnyRef]))
      case Left(exception) ⇒
        messageBuilder.setException(ExceptionProtocol.newBuilder
          .setClassname(exception.getClass.getName)
          .setMessage(Option(exception.getMessage).getOrElse(""))
          .build)
    }

    if (senderOption.isDefined)
      messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }
}
