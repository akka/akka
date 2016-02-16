/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ DynamicAccess, ActorRef, ActorSystem }
import akka.event.Logging.{ Warning, Error }
import com.typesafe.config.Config

import scala.util.Try

/**
 * Instrumentation implementation that prints activity. Can be used for println debugging.
 */
class PrintInstrumentation(dynamicAccess: DynamicAccess, config: Config) extends ActorInstrumentation {

  private val muted: Boolean = Try { config.getBoolean("akka.print-instrumentation.muted") } getOrElse false

  def print(message: String) = if (!muted) println("[instrumentation] " + message)

  override def access[T <: ActorInstrumentation](instrumentationClass: Class[T]): T =
    (if (instrumentationClass isInstance this) this else null).asInstanceOf[T]

  override def systemStarted(system: ActorSystem): Unit = {
    print(s"system started: $system")
  }

  override def systemShutdown(system: ActorSystem): Unit = {
    print(s"system shutdown: $system")
  }

  override def actorCreated(actorRef: ActorRef): Unit = {
    print(s"actor created: $actorRef")
  }

  override def actorStarted(actorRef: ActorRef): Unit = {
    print(s"actor started: $actorRef")
  }

  override def actorStopped(actorRef: ActorRef): Unit = {
    print(s"actor stopped: $actorRef")
  }

  override def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = {
    print(s"actor told: $actorRef ! $message (sender = $sender)")
    ActorInstrumentation.EmptyContext
  }

  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = {
    print(s"actor received: $actorRef ! $message (sender = $sender, context = $context)")
    ActorInstrumentation.EmptyContext
  }

  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = {
    print(s"actor completed: $actorRef ! $message (sender = $sender)")
  }

  override def clearContext(): Unit = {
    print(s"clear context:")
  }

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    print(s"event unhandled message: $actorRef $message (sender = $sender)")
  }

  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    print(s"event dead letter: $actorRef $message (sender = $sender)")
  }

  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit = {
    print(s"event log warning: $actorRef (warning = $warning)")
  }

  override def eventLogError(actorRef: ActorRef, error: Error): Unit = {
    print(s"event log error: $actorRef (error = $error)")
  }

  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit = {
    print(s"event actor failure: $actorRef (cause = $cause)")
  }
}
