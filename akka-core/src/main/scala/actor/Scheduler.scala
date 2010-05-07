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
 */

package se.scalablesolutions.akka.actor

import java.util.concurrent._

import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy}
import se.scalablesolutions.akka.util.Logging

case object UnSchedule
case class SchedulerException(msg: String, e: Throwable) extends RuntimeException(msg, e)

/**
 * Rework of David Pollak's ActorPing class in the Lift Project
 * which is licensed under the Apache 2 License.
 */
class ScheduleActor(val receiver: ActorRef, val future: ScheduledFuture[AnyRef]) extends Actor with Logging {
  lifeCycle = Some(LifeCycle(Permanent))

  def receive = {
    case UnSchedule =>
      Scheduler.stopSupervising(self)
      future.cancel(true)
      exit
  }
}

object Scheduler extends Actor {
  private var service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)
  private val schedulers = new ConcurrentHashMap[ActorRef, ActorRef]
  faultHandler = Some(OneForOneStrategy(5, 5000))
  trapExit = List(classOf[Throwable])
  start

  def schedule(receiver: ActorRef, message: AnyRef, initialDelay: Long, delay: Long, timeUnit: TimeUnit) = {
    try {
      startLink(new ActorRef(() => new ScheduleActor(
        receiver,
        service.scheduleAtFixedRate(new java.lang.Runnable {
          def run = receiver ! message;
        }, initialDelay, delay, timeUnit).asInstanceOf[ScheduledFuture[AnyRef]])))
    } catch {
      case e => throw SchedulerException(message + " could not be scheduled on " + receiver, e)
    }
  }

  def restart = service = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory)

  def stopSupervising(actorRef: ActorRef) = {
    unlink(actorRef)
    schedulers.remove(actorRef)
  }

  override def shutdown = {
    import scala.collection.JavaConversions._
    schedulers.values.foreach(_ ! UnSchedule)
    service.shutdown
  }

  def receive = {
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


