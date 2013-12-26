/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor.{ ActorRef, ActorSystem }
import akka.util.ByteString
import com.typesafe.config.Config
import java.util.concurrent.atomic.AtomicLong

object CountTracer {
  def apply(system: ActorSystem): CountTracer = Tracer[CountTracer](system)

  class Counts {
    val systemStarted = new AtomicLong(0L)
    val systemShutdown = new AtomicLong(0L)
    val actorTold = new AtomicLong(0L)
    val actorReceived = new AtomicLong(0L)
    val actorCompleted = new AtomicLong(0L)
    val remoteMessageSent = new AtomicLong(0L)
    val remoteMessageReceived = new AtomicLong(0L)
    val remoteMessageCompleted = new AtomicLong(0L)

    def reset(): Unit = {
      systemStarted.set(0L)
      systemShutdown.set(0L)
      actorTold.set(0L)
      actorReceived.set(0L)
      actorCompleted.set(0L)
      remoteMessageSent.set(0L)
      remoteMessageReceived.set(0L)
      remoteMessageCompleted.set(0L)
    }
  }
}

/**
 * Tracer implementation that counts the calls to the SPI. For testing.
 */
class CountTracer(config: Config) extends NoContextTracer {
  val counts = new CountTracer.Counts

  def systemStarted(system: ActorSystem): Unit =
    counts.systemStarted.incrementAndGet

  def systemShutdown(system: ActorSystem): Unit =
    counts.systemShutdown.incrementAndGet

  def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    counts.actorTold.incrementAndGet

  def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    counts.actorReceived.incrementAndGet

  def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit =
    counts.actorCompleted.incrementAndGet

  def remoteMessageSent(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit =
    counts.remoteMessageSent.incrementAndGet

  def remoteMessageReceived(actorRef: ActorRef, message: Any, size: Int, sender: ActorRef): Unit =
    counts.remoteMessageReceived.incrementAndGet
}
