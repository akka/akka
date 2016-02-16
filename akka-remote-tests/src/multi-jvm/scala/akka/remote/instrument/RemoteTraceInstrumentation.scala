/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.instrument

import akka.actor.{ ActorSystem, ActorRef }
import akka.instrument.{ ActorInstrumentation, EmptyRemoteInstrumentation, RemoteInstrumentation, Trace }
import akka.serialization.SerializationExtension
import com.typesafe.config.Config

/**
 * Example instrumentation implementation that threads a trace identifier
 * through message flows using the context and a thread-local.
 */
class RemoteTraceInstrumentation(config: Config) extends EmptyRemoteInstrumentation {
  private var system: ActorSystem = _

  override def systemStarted(system: ActorSystem): Unit = this.system = system

  override def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = appendContext(actorRef)

  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = {
    setContext(context)
    ActorInstrumentation.EmptyContext
  }

  override def clearContext(): Unit = Trace.clear()

  override val remoteIdentifier: Int = 4711

  override def remoteActorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = getContext(actorRef)

  override def remoteMessageSent(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: AnyRef): Array[Byte] = serializeContext(context)

  override def remoteMessageReceived(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: Array[Byte]): Unit = setContext(deserializeContext(context))

  private def appendContext(actorRef: ActorRef): AnyRef = getContext(actorRef) match {
    case trace: Trace ⇒ trace.copy(identifier = s"${trace.identifier} <- ${actorRef.path.name}")
    case _            ⇒ ActorInstrumentation.EmptyContext
  }

  private def getContext(actorRef: ActorRef): AnyRef =
    if (actorRef.path.parent.name == "user") Trace.value else ActorInstrumentation.EmptyContext

  private def setContext(context: AnyRef): Unit =
    if (context ne ActorInstrumentation.EmptyContext) Trace.value = context

  private def serializeContext(context: AnyRef): Array[Byte] =
    SerializationExtension(system).serialize(context).getOrElse(RemoteInstrumentation.EmptySerializedContext)

  private def deserializeContext(context: Array[Byte]): AnyRef =
    SerializationExtension(system).deserialize(context, classOf[String]).getOrElse(ActorInstrumentation.EmptyContext)
}
