/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.Logging.{ Warning, Error }
import com.typesafe.config.Config
import java.util.concurrent.atomic.AtomicLong

object CountInstrumentation {
  def apply(system: ActorSystem): CountInstrumentation = ActorInstrumentation[CountInstrumentation](system)

  class Counts {
    val systemStarted = new AtomicLong(0L)
    val systemShutdown = new AtomicLong(0L)
    val actorCreated = new AtomicLong(0L)
    val actorStarted = new AtomicLong(0L)
    val actorShutdown = new AtomicLong(0L)
    val actorTold = new AtomicLong(0L)
    val actorReceived = new AtomicLong(0L)
    val actorCompleted = new AtomicLong(0L)
    val eventUnhandled = new AtomicLong(0L)
    val eventDeadLetter = new AtomicLong(0L)
    val eventLogWarning = new AtomicLong(0L)
    val eventLogError = new AtomicLong(0L)
    val eventActorFailure = new AtomicLong(0L)

    def reset(): Unit = {
      systemStarted.set(0L)
      systemShutdown.set(0L)
      actorCreated.set(0L)
      actorStarted.set(0L)
      actorShutdown.set(0L)
      actorTold.set(0L)
      actorReceived.set(0L)
      actorCompleted.set(0L)
      eventUnhandled.set(0L)
      eventDeadLetter.set(0L)
      eventLogWarning.set(0L)
      eventLogError.set(0L)
      eventActorFailure.set(0L)
    }
  }
}

/**
 * Instrumentation implementation that counts the calls to the SPI. For testing.
 */
class CountInstrumentation(config: Config) extends EmptyActorInstrumentation {
  val counts = new CountInstrumentation.Counts

  override def systemStarted(system: ActorSystem): Unit =
    counts.systemStarted.incrementAndGet

  override def systemShutdown(system: ActorSystem): Unit =
    counts.systemShutdown.incrementAndGet

  override def actorCreated(actorRef: ActorRef): Unit =
    counts.actorCreated.incrementAndGet()

  override def actorStarted(actorRef: ActorRef): Unit =
    counts.actorStarted.incrementAndGet()

  override def actorStopped(actorRef: ActorRef): Unit =
    counts.actorShutdown.incrementAndGet()

  override def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = {
    counts.actorTold.incrementAndGet
    ActorInstrumentation.EmptyContext
  }

  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = {
    counts.actorReceived.incrementAndGet
    ActorInstrumentation.EmptyContext
  }

  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit =
    counts.actorCompleted.incrementAndGet

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    counts.eventUnhandled.incrementAndGet

  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    counts.eventDeadLetter.incrementAndGet

  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit =
    counts.eventLogWarning.incrementAndGet

  override def eventLogError(actorRef: ActorRef, error: Error): Unit =
    counts.eventLogError.incrementAndGet

  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit =
    counts.eventActorFailure.incrementAndGet
}
