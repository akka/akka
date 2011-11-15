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
import akka.AkkaException

case class SchedulerException(msg: String, e: Throwable) extends AkkaException(msg, e) {
  def this(msg: String) = this(msg, null)
}

trait JScheduler {
  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable
  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): Cancellable
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): Cancellable
}

abstract class Scheduler extends JScheduler {
  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable

  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): Cancellable

  def schedule(receiver: ActorRef, message: Any, initialDelay: Duration, delay: Duration): Cancellable =
    schedule(receiver, message, initialDelay.toNanos, delay.toNanos, TimeUnit.NANOSECONDS)

  def schedule(f: () ⇒ Unit, initialDelay: Duration, delay: Duration): Cancellable =
    schedule(f, initialDelay.toNanos, delay.toNanos, TimeUnit.NANOSECONDS)

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): Cancellable =
    scheduleOnce(receiver, message, delay.length, delay.unit)

  def scheduleOnce(f: () ⇒ Unit, delay: Duration): Cancellable =
    scheduleOnce(f, delay.length, delay.unit)
}

trait Cancellable {
  def cancel(): Unit

  def isCancelled: Boolean
}