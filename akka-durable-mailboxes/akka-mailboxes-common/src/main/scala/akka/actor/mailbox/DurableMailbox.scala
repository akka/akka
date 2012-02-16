/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.{ ActorContext, ActorRef, ExtendedActorSystem }
import akka.dispatch.{ Envelope, DefaultSystemMessageQueue, CustomMailbox }
import akka.remote.MessageSerializer
import akka.remote.RemoteProtocol.{ ActorRefProtocol, RemoteMessageProtocol }

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

abstract class DurableMailbox(val owner: ActorContext) extends CustomMailbox(owner) with DefaultSystemMessageQueue {
  import DurableExecutableMailboxConfig._

  def system: ExtendedActorSystem = owner.system.asInstanceOf[ExtendedActorSystem]
  def ownerPath = owner.self.path
  val ownerPathString = ownerPath.elements.mkString("/")
  val name = "mailbox_" + Name.replaceAllIn(ownerPathString, "_")

}

trait DurableMessageSerialization { this: DurableMailbox ⇒

  def serialize(durableMessage: Envelope): Array[Byte] = {

    def serializeActorRef(ref: ActorRef): ActorRefProtocol = ActorRefProtocol.newBuilder.setPath(ref.path.toString).build

    val message = MessageSerializer.serialize(system, durableMessage.message.asInstanceOf[AnyRef])
    val builder = RemoteMessageProtocol.newBuilder
      .setMessage(message)
      .setRecipient(serializeActorRef(owner.self))
      .setSender(serializeActorRef(durableMessage.sender))

    builder.build.toByteArray
  }

  def deserialize(bytes: Array[Byte]): Envelope = {

    def deserializeActorRef(refProtocol: ActorRefProtocol): ActorRef = system.actorFor(refProtocol.getPath)

    val durableMessage = RemoteMessageProtocol.parseFrom(bytes)
    val message = MessageSerializer.deserialize(system, durableMessage.getMessage)
    val sender = deserializeActorRef(durableMessage.getSender)

    new Envelope(message, sender)(system)
  }

}

