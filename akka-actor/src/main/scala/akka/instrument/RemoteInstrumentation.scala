/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ ActorSystem, ActorRef }
import akka.event.Logging.{ Error, Warning }

/**
 * Remote instrumentation, for remote message sends and serializing contexts.
 */
object RemoteInstrumentation {
  /**
   * No serialization identifier.
   */
  val NoIdentifier: Int = 0

  /**
   * Empty placeholder (null) for when there is no serialized context.
   */
  val EmptySerializedContext: Array[Byte] = null
}

/**
 * Remote instrumentation, for remote message sends and serializing contexts.
 *
 * A remote message flow will have the following calls:
 *
 *  - `remoteActorTold` when the message is sent with `!` or `tell`, returns an optional context
 *  - `remoteMessageSent` when the message is serialized to be sent remotely, with optional context
 *  - `remoteMessageReceived` on the receiving side, before the message is dispatched
 *  - `actorTold` to the recipient actor on the remote side
 *  - `clearContext` after message dispatch is complete
 */
abstract class RemoteInstrumentation extends ActorInstrumentation {

  /**
   * Unique identifier used for this instrumentation when serializing contexts.
   * Similar to [[akka.serialization.Serializer#identifier]].
   */
  def remoteIdentifier: Int

  /**
   * Record remote actor told - on message send with `!` or `tell`.
   *
   * @param actorRef the recipient [[akka.actor.ActorRef]] of the remote message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   * @return the context that will travel with this message (serialized by `remoteMessageSent`)
   */
  def remoteActorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef

  /**
   * Record remote message sent - when remote message is going over the wire.
   *
   * @param actorRef the recipient [[akka.actor.ActorRef]] of the remote message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   * @param size the size in bytes of the serialized user message object
   * @param context the context associated with this message to be serialized
   * @return the serialized context or [[RemoteInstrumentation.EmptySerializedContext]]
   */
  def remoteMessageSent(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: AnyRef): Array[Byte]

  /**
   * Record remote message received - before the processing of the remote message.
   *
   * @param actorRef the recipient [[akka.actor.ActorRef]] of the remote message
   * @param message the (deserialized) message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   * @param size the size in bytes of the serialized user message object
   * @param context the serialized context or [[RemoteInstrumentation.EmptySerializedContext]]
   */
  def remoteMessageReceived(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: Array[Byte]): Unit
}

abstract class EmptyRemoteInstrumentation extends RemoteInstrumentation {
  override def access[T <: ActorInstrumentation](instrumentationClass: Class[T]): T =
    (if (instrumentationClass isInstance this) this else null).asInstanceOf[T]

  override def systemStarted(system: ActorSystem): Unit = ()
  override def systemShutdown(system: ActorSystem): Unit = ()

  override def actorCreated(actorRef: ActorRef): Unit = ()
  override def actorStarted(actorRef: ActorRef): Unit = ()
  override def actorStopped(actorRef: ActorRef): Unit = ()

  override def actorTold(receiver: ActorRef, message: Any, sender: ActorRef): AnyRef = ActorInstrumentation.EmptyContext
  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = ActorInstrumentation.EmptyContext
  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = ()

  override def clearContext(): Unit = ()

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit = ()
  override def eventLogError(actorRef: ActorRef, error: Error): Unit = ()
  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit = ()

  override def remoteIdentifier: Int = RemoteInstrumentation.NoIdentifier
  override def remoteActorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = ActorInstrumentation.EmptyContext
  override def remoteMessageSent(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: AnyRef): Array[Byte] = RemoteInstrumentation.EmptySerializedContext
  override def remoteMessageReceived(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: Array[Byte]): Unit = ()
}
