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
package akka.actor

import java.util.concurrent._
import akka.util.Duration
import org.jboss.netty.akka.util.{ HashedWheelTimer, TimerTask }
import akka.AkkaException
import org.jboss.netty.akka.util.{ Timeout ⇒ TimeOut }

case class SchedulerException(msg: String, e: Throwable) extends AkkaException(msg, e) {
  def this(msg: String) = this(msg, null)
}

trait JScheduler {
  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): TimeOut
  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): TimeOut
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): TimeOut
}

abstract class Scheduler extends JScheduler {
  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): TimeOut

  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): TimeOut

  def schedule(receiver: ActorRef, message: Any, initialDelay: Duration, delay: Duration): TimeOut =
    schedule(receiver, message, initialDelay.toNanos, delay.toNanos, TimeUnit.NANOSECONDS)

  def schedule(f: () ⇒ Unit, initialDelay: Duration, delay: Duration): TimeOut =
    schedule(f, initialDelay.toNanos, delay.toNanos, TimeUnit.NANOSECONDS)

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): TimeOut =
    scheduleOnce(receiver, message, delay.length, delay.unit)

  def scheduleOnce(f: () ⇒ Unit, delay: Duration): TimeOut =
    scheduleOnce(f, delay.length, delay.unit)
}

class DefaultScheduler(hashedWheelTimer: HashedWheelTimer) extends Scheduler {
  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): TimeOut =
    hashedWheelTimer.newTimeout(createContinuousTask(receiver, message, delay, timeUnit), initialDelay, timeUnit)

  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): TimeOut =
    hashedWheelTimer.newTimeout(createSingleTask(runnable), delay, timeUnit)

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): TimeOut =
    hashedWheelTimer.newTimeout(createSingleTask(receiver, message), delay, timeUnit)

  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): TimeOut =
    hashedWheelTimer.newTimeout(createContinuousTask(f, delay, timeUnit), initialDelay, timeUnit)

  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): TimeOut =
    hashedWheelTimer.newTimeout(createSingleTask(f), delay, timeUnit)

  private def createSingleTask(runnable: Runnable): TimerTask =
    new TimerTask() { def run(timeout: org.jboss.netty.akka.util.Timeout) { runnable.run() } }

  private def createSingleTask(receiver: ActorRef, message: Any) =
    new TimerTask { def run(timeout: org.jboss.netty.akka.util.Timeout) { receiver ! message } }

  private def createContinuousTask(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit) = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        receiver ! message
        timeout.getTimer.newTimeout(this, delay, timeUnit)
      }
    }
  }

  private def createSingleTask(f: () ⇒ Unit): TimerTask =
    new TimerTask { def run(timeout: org.jboss.netty.akka.util.Timeout) { f() } }

  private def createContinuousTask(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        f()
        timeout.getTimer.newTimeout(this, delay, timeUnit)
      }
    }
  }

  private[akka] def stop() = hashedWheelTimer.stop()
}