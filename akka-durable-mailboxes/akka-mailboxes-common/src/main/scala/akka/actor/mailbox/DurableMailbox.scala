/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.util.ReflectiveAccess
import java.lang.reflect.InvocationTargetException
import akka.AkkaException
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.SerializedActorRef
import akka.dispatch.Envelope
import akka.dispatch.DefaultSystemMessageQueue
import akka.dispatch.Dispatcher
import akka.dispatch.CustomMailbox
import akka.dispatch.MailboxType
import akka.dispatch.MessageDispatcher
import akka.dispatch.MessageQueue
import akka.remote.MessageSerializer
import akka.remote.RemoteProtocol.ActorRefProtocol
import akka.remote.RemoteProtocol.MessageProtocol
import akka.remote.RemoteProtocol.RemoteMessageProtocol
import akka.remote.RemoteActorRefProvider
import akka.remote.netty.NettyRemoteServer
import akka.serialization.Serialization
import com.typesafe.config.Config
import akka.dispatch.CustomMailboxType

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

class DurableMailboxException private[akka] (message: String, cause: Throwable) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
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

    new Envelope(message, sender)
  }

}

