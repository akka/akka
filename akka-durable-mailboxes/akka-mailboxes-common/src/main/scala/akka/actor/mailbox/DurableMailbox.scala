/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.{ ActorContext, ActorRef, ExtendedActorSystem }
import akka.remote.MessageSerializer
import akka.remote.RemoteProtocol.{ ActorRefProtocol, RemoteMessageProtocol }
import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.util.duration._
import akka.util.Duration
import akka.dispatch.{ ExecutionContext, Envelope, MessageQueue }

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

abstract class DurableMessageQueue(val owner: ActorContext) extends MessageQueue {
  import DurableExecutableMailboxConfig._

  def system: ExtendedActorSystem = owner.system.asInstanceOf[ExtendedActorSystem]
  def ownerPath = owner.self.path
  val ownerPathString = ownerPath.elements.mkString("/")
  val name = "mailbox_" + Name.replaceAllIn(ownerPathString, "_")

  // TODO: Arbitrary defaults for now
  val circuitBreakerMaxFailures: Int = 5
  val circuitBreakerResetTimeout: Duration = 10 seconds
  val circuitBreakerCallTimeout: Duration = 1 seconds

  val circuitBreaker = new CircuitBreaker(system.scheduler, circuitBreakerMaxFailures, circuitBreakerCallTimeout, circuitBreakerResetTimeout)(ExecutionContext.defaultExecutionContext(system))
  def withCircuitBreaker[T](body: ⇒ T): T = circuitBreaker.withCircuitBreaker(body)
}

trait DurableMessageSerialization { this: DurableMessageQueue ⇒

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

/**
 * Conventional organization of durable mailbox settings:
 *
 * {{{
 * my-durable-dispatcher {
 *   mailbox-type = "my.durable.mailbox"
 *   my-durable-mailbox {
 *     setting1 = 1
 *     setting2 = 2
 *   }
 * }
 * }}}
 *
 * where name=“my-durable-mailbox” in this example.
 */
trait DurableMailboxSettings {
  /**
   * A reference to the enclosing actor system.
   */
  def systemSettings: ActorSystem.Settings

  /**
   * A reference to the config section which the user specified for this mailbox’s dispatcher.
   */
  def userConfig: Config

  /**
   * The extracted config section for this mailbox, which is the “name”
   * section (if that exists), falling back to system defaults. Typical
   * implementation looks like:
   *
   * {{{
   * val config = initialize
   * }}}
   */
  def config: Config

  /**
   * Name of this mailbox type for purposes of configuration scoping. Reference
   * defaults go into “akka.actor.mailbox.<name>”.
   */
  def name: String

  /**
   * Obtain default extracted mailbox config section from userConfig and system.
   */
  def initialize: Config =
    if (userConfig.hasPath(name))
      userConfig.getConfig(name).withFallback(systemSettings.config.getConfig("akka.actor.mailbox." + name))
    else systemSettings.config.getConfig("akka.actor.mailbox." + name)
}

