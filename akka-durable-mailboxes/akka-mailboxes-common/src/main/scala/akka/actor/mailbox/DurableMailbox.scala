/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import MailboxProtocol._

import akka.actor.{ Actor, ActorRef, NullChannel }
import akka.dispatch._
import akka.event.EventHandler
import akka.cluster.MessageSerializer
import akka.cluster.protocol.RemoteProtocol.MessageProtocol
import akka.AkkaException

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait DurableMailboxBase {
  def serialize(message: MessageInvocation): Array[Byte]
  def deserialize(bytes: Array[Byte]): MessageInvocation
}

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

class DurableMailboxException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class DurableExecutableMailbox(owner: ActorRef) extends MessageQueue with ExecutableMailbox with DurableMailboxBase {
  import DurableExecutableMailboxConfig._

  val ownerAddress = owner.address
  val name = "mailbox_" + Name.replaceAllIn(ownerAddress, "_")

  EventHandler.debug(this, "Creating %s mailbox [%s]".format(getClass.getName, name))

  val dispatcher: Dispatcher = owner.dispatcher match {
    case e: Dispatcher ⇒ e
    case _             ⇒ null
  }

  //TODO: switch to RemoteProtocol
  def serialize(durableMessage: MessageInvocation) = {
    val message = MessageSerializer.serialize(durableMessage.message.asInstanceOf[AnyRef])
    val builder = DurableMailboxMessageProtocol.newBuilder
      .setOwnerAddress(ownerAddress)
      .setMessage(message.toByteString)
    durableMessage.channel match {
      case a: ActorRef ⇒ builder.setSenderAddress(a.address)
      case _           ⇒
    }
    builder.build.toByteArray
  }

  //TODO: switch to RemoteProtocol
  def deserialize(bytes: Array[Byte]) = {
    val durableMessage = DurableMailboxMessageProtocol.parseFrom(bytes)
    val messageProtocol = MessageProtocol.parseFrom(durableMessage.getMessage)
    val message = MessageSerializer.deserialize(messageProtocol)
    val ownerAddress = durableMessage.getOwnerAddress
    val owner = Actor.registry.actorFor(ownerAddress).getOrElse(
      throw new DurableMailboxException("No actor could be found for address [" + ownerAddress + "], could not deserialize message."))

    val senderOption = if (durableMessage.hasSenderAddress) {
      Actor.registry.actorFor(durableMessage.getSenderAddress)
    } else None
    val sender = senderOption match {
      case Some(ref) ⇒ ref
      case None      ⇒ NullChannel
    }

    new MessageInvocation(owner, message, sender)
  }
}
