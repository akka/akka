/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor.{ ActorRef, ActorSystem }
import akka.util.ByteString
import com.typesafe.config.Config

/**
 * Tracer implementation that simply prints activity. Can be used for println debugging.
 */
class PrintTracer(config: Config) extends NoContextTracer {
  def trace(message: String) = println("[trace] " + message)

  def systemStarted(system: ActorSystem): Unit =
    trace(s"system started: $system")

  def systemShutdown(system: ActorSystem): Unit =
    trace(s"system shutdown: $system")

  def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    trace(s"actor told: $actorRef ! $message (sender = $sender)")

  def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    trace(s"actor received: $actorRef ! $message (sender = $sender)")

  def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    trace(s"actor completed: $actorRef ! $message (sender = $sender)")

  def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit =
    trace(s"remote message sent: $actorRef ! $message (size = $size bytes, sender = $sender)")

  def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit =
    trace(s"remote message received: $actorRef ! $message (size = $size bytes, sender = $sender)")
}
