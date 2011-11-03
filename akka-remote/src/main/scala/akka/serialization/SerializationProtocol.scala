/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.actor._
import akka.actor.DeploymentConfig._
import akka.remote._
import RemoteProtocol._

import java.net.InetSocketAddress

import com.google.protobuf.ByteString

import com.eaio.uuid.UUID

class RemoteActorSerialization(remote: RemoteSupport) {

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol = {
    val rep = remote.app.provider.serialize(actor)
    ActorRefProtocol.newBuilder.setAddress(rep.address).setHost(rep.hostname).setPort(rep.port).build
  }

  def createRemoteMessageProtocolBuilder(
    recipient: Either[ActorRef, ActorRefProtocol],
    message: Either[Throwable, Any],
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(recipient.fold(toRemoteActorRefProtocol _, identity))

    message match {
      case Right(message) ⇒
        messageBuilder.setMessage(MessageSerializer.serialize(remote.app, message.asInstanceOf[AnyRef]))
      case Left(exception) ⇒
        messageBuilder.setException(ExceptionProtocol.newBuilder
          .setClassname(exception.getClass.getName)
          .setMessage(Option(exception.getMessage).getOrElse(""))
          .build)
    }

    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }
}
