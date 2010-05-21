/*
 * Copyright 2007 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Rework of David Pollak's ActorPing class in the Lift Project
 * which is licensed under the Apache 2 License.
 */
package se.scalablesolutions.akka.actor

import java.util.concurrent._

import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.util.Logging

object Scheduler {
  import Actor._

  case object UnSchedule
  case class SchedulerException(msg: String, e: Throwable) extends RuntimeException(msg, e)

  private var service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)
  private val schedulers = new ConcurrentHashMap[ActorRef, ActorRef]

  def schedule(receiver: ActorRef, message: AnyRef, initialDelay: Long, delay: Long, timeUnit: TimeUnit) = {
    try {
      val future = service.scheduleAtFixedRate(
        new Runnable { def run = receiver ! message },
        initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]]
      val scheduler = actorOf(new ScheduleActor(future)).start
      schedulers.put(scheduler, scheduler)
    } catch {
      case e => throw SchedulerException(message + " could not be scheduled on " + receiver, e)
    }
  }

  def unschedule(actorRef: ActorRef) = {
    actorRef ! UnSchedule
    schedulers.remove(actorRef)
  }

  def shutdown = {
    import scala.collection.JavaConversions._
    schedulers.values.foreach(_ ! UnSchedule)
    schedulers.clear
    service.shutdown
  }

  def restart = {
    shutdown
    service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)
  }
}

private class ScheduleActor(future: ScheduledFuture[AnyRef]) extends Actor with Logging {
  def receive = {
    case Scheduler.UnSchedule =>
      future.cancel(true)
      exit
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


