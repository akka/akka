/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.{ ActorContext, ActorRef }
import akka.dispatch.{ Envelope, DefaultSystemMessageQueue, CustomMailbox }
import akka.remote.MessageSerializer
import akka.remote.RemoteProtocol.{ ActorRefProtocol, RemoteMessageProtocol }

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

abstract class DurableMailbox(owner: ActorContext) extends CustomMailbox(owner) with DefaultSystemMessageQueue {
  import DurableExecutableMailboxConfig._

  def system = owner.system
  def ownerPath = owner.self.path
  val ownerPathString = ownerPath.elements.mkString("/")
  val name = "mailbox_" + Name.replaceAllIn(ownerPathString, "_")

}

trait DurableMessageSerialization {

  def owner: ActorContext

  def serialize(durableMessage: Envelope): Array[Byte] = {

    def serializeActorRef(ref: ActorRef): ActorRefProtocol = ActorRefProtocol.newBuilder.setPath(ref.path.toString).build

    val message = MessageSerializer.serialize(owner.system, durableMessage.message.asInstanceOf[AnyRef])
    val builder = RemoteMessageProtocol.newBuilder
      .setMessage(message)
      .setRecipient(serializeActorRef(owner.self))
      .setSender(serializeActorRef(durableMessage.sender))

    builder.build.toByteArray
  }

  def deserialize(bytes: Array[Byte]): Envelope = {

    def deserializeActorRef(refProtocol: ActorRefProtocol): ActorRef = owner.system.actorFor(refProtocol.getPath)

    val durableMessage = RemoteMessageProtocol.parseFrom(bytes)
    val message = MessageSerializer.deserialize(owner.system, durableMessage.getMessage)
    val sender = deserializeActorRef(durableMessage.getSender)

    new Envelope(message, sender)(owner.system)
  }

}

