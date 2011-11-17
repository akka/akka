/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.util.ReflectiveAccess
import java.lang.reflect.InvocationTargetException

import akka.AkkaException
import akka.actor.ActorCell
import akka.actor.ActorRef
import akka.actor.SerializedActorRef
import akka.config.Configuration
import akka.dispatch.Envelope
import akka.dispatch.DefaultSystemMessageQueue
import akka.dispatch.Dispatcher
import akka.dispatch.Mailbox
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

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

class DurableMailboxException private[akka] (message: String, cause: Throwable) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class DurableMailbox(owner: ActorCell) extends Mailbox(owner) with DefaultSystemMessageQueue {
  import DurableExecutableMailboxConfig._

  def system = owner.system
  def ownerPath = owner.self.path
  val ownerPathString = ownerPath.path.mkString("/")
  val name = "mailbox_" + Name.replaceAllIn(ownerPathString, "_")

}

trait DurableMessageSerialization {

  def owner: ActorCell

  def serialize(durableMessage: Envelope): Array[Byte] = {

    def serializeActorRef(ref: ActorRef): ActorRefProtocol = {
      val serRef = owner.system.provider.serialize(ref)
      ActorRefProtocol.newBuilder.setPath(serRef.path).setHost(serRef.hostname).setPort(serRef.port).build
    }

    val message = MessageSerializer.serialize(owner.system, durableMessage.message.asInstanceOf[AnyRef])
    val builder = RemoteMessageProtocol.newBuilder
      .setMessage(message)
      .setRecipient(serializeActorRef(owner.self))
      .setSender(serializeActorRef(durableMessage.sender))

    builder.build.toByteArray
  }

  def deserialize(bytes: Array[Byte]): Envelope = {

    def deserializeActorRef(refProtocol: ActorRefProtocol): ActorRef = {
      val serRef = SerializedActorRef(refProtocol.getHost, refProtocol.getPort, refProtocol.getPath)
      owner.system.provider.deserialize(serRef).getOrElse(owner.system.deadLetters)
    }

    val durableMessage = RemoteMessageProtocol.parseFrom(bytes)
    val message = MessageSerializer.deserialize(owner.system, durableMessage.getMessage)
    val sender = deserializeActorRef(durableMessage.getSender)

    new Envelope(message, sender)
  }

}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class DurableMailboxType(mailboxFQN: String) extends MailboxType {
  val constructorSignature = Array[Class[_]](classOf[ActorCell])

  val mailboxClass: Class[_] = ReflectiveAccess.getClassFor(mailboxFQN, classOf[ActorCell].getClassLoader) match {
    case Right(clazz) ⇒ clazz
    case Left(exception) ⇒
      val cause = exception match {
        case i: InvocationTargetException ⇒ i.getTargetException
        case _                            ⇒ exception
      }
      throw new DurableMailboxException("Cannot find class [%s] due to: %s".format(mailboxFQN, cause.toString))
  }

  //TODO take into consideration a mailboxConfig parameter so one can have bounded mboxes and capacity etc
  def create(receiver: ActorCell): Mailbox = {
    ReflectiveAccess.createInstance[AnyRef](mailboxClass, constructorSignature, Array[AnyRef](receiver)) match {
      case Right(instance) ⇒ instance.asInstanceOf[Mailbox]
      case Left(exception) ⇒
        val cause = exception match {
          case i: InvocationTargetException ⇒ i.getTargetException
          case _                            ⇒ exception
        }
        throw new DurableMailboxException("Cannot instantiate [%s] due to: %s".format(mailboxClass.getName, cause.toString))
    }
  }
}

case object RedisDurableMailboxType extends DurableMailboxType("akka.actor.mailbox.RedisBasedMailbox")
case object MongoDurableMailboxType extends DurableMailboxType("akka.actor.mailbox.MongoBasedMailbox")
case object BeanstalkDurableMailboxType extends DurableMailboxType("akka.actor.mailbox.BeanstalkBasedMailbox")
case object FileDurableMailboxType extends DurableMailboxType("akka.actor.mailbox.FileBasedMailbox")
case object ZooKeeperDurableMailboxType extends DurableMailboxType("akka.actor.mailbox.ZooKeeperBasedMailbox")
case class FqnDurableMailboxType(mailboxFQN: String) extends DurableMailboxType(mailboxFQN)

/**
 * Configurator for the DurableMailbox
 * Do not forget to specify the "storage", valid values are "redis", "beanstalkd", "zookeeper", "mongodb", "file",
 * or a full class name of the Mailbox implementation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DurableMailboxConfigurator {
  // TODO PN #896: when and how is this class supposed to be used? Can we remove it?

  def mailboxType(config: Configuration): MailboxType = {
    val storage = config.getString("storage") map {
      case "redis"     ⇒ RedisDurableMailboxType
      case "mongodb"   ⇒ MongoDurableMailboxType
      case "beanstalk" ⇒ BeanstalkDurableMailboxType
      case "zookeeper" ⇒ ZooKeeperDurableMailboxType
      case "file"      ⇒ FileDurableMailboxType
      case fqn         ⇒ FqnDurableMailboxType(fqn)
    }

    storage.getOrElse(throw new DurableMailboxException("No 'storage' defined for durable mailbox"))
  }
}
