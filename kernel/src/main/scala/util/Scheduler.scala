/**
 *  Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.util

import java.util.concurrent._
import kernel.actor.{OneForOneStrategy, Actor}

import org.scala_tools.javautils.Imports._

case object Schedule
case object UnSchedule
case class SchedulerException(msg: String, e: Throwable) extends RuntimeException(msg, e)

/**
 * Based on David Pollak's ActorPing class in the Lift Project.
 * Licensed under Apache 2 License.
 */
class ScheduleActor(val receiver: Actor, val future: ScheduledFuture[AnyRef]) extends Actor with Logging {
  receiver ! Schedule

  def receive: PartialFunction[Any, Unit] = {
    case UnSchedule =>
      Scheduler.stopSupervising(this)
      future.cancel(true)
      stop
  }
}

object Scheduler extends Actor {
  private var service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)
  private val schedulers = new ConcurrentHashMap[Actor, Actor]
  faultHandler = Some(OneForOneStrategy(5, 5000))
  trapExit = true

  def schedule(receiver: Actor, message: AnyRef, initialDelay: Long, delay: Long, timeUnit: TimeUnit) = {
    try {
      startLink(new ScheduleActor(
        receiver,
        service.scheduleAtFixedRate(new java.lang.Runnable {
          def run = receiver ! message;
        }, initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]))
    } catch {
      case e => throw SchedulerException(message + " could not be scheduled on " + receiver, e)
    }
  }

  def restart = service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)

  def stopSupervising(actor: Actor) = {
    unlink(actor)
    schedulers.remove(actor)
  }
  
  override def shutdown = {
    schedulers.values.asScala.foreach(_ ! UnSchedule)
    service.shutdown
  }

  def receive: PartialFunction[Any, Unit] = {
    case _ => {} // ignore all messages
  }
}

private object SchedulerThreadFactory extends ThreadFactory {
  private var count = 0
  val threadFactory = Executors.defaultThreadFactory()
  def newThread(r: Runnable): Thread = {
    val thread = threadFactory.newThread(r)
    thread.setName("Scheduler-" + count)
    thread.setDaemon(true)
    thread
  }
}
