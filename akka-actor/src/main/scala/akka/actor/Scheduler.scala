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
package akka.actor

import akka.util.Duration

  /**
   * An Akka scheduler service. This one needs one special behavior: if
   * Closeable, it MUST execute all outstanding tasks upon .close() in order
   * to properly shutdown all dispatchers.
   * 
   * Furthermore, this timer service MUST throw IllegalStateException if it
   * cannot schedule a task. Once scheduled, the task MUST be executed. If
   * executed upon close(), the task may execute before its timeout.
   */
trait Scheduler {
  /**
   * Schedules a message to be sent repeatedly with an initial delay and frequency.
   * E.g. if you would like a message to be sent immediately and thereafter every 500ms you would set
   * delay = Duration.Zero and frequency = Duration(500, TimeUnit.MILLISECONDS)
   */
  def schedule(initialDelay: Duration, frequency: Duration, receiver: ActorRef, message: Any): Cancellable

  /**
   * Schedules a function to be run repeatedly with an initial delay and a frequency.
   * E.g. if you would like the function to be run after 2 seconds and thereafter every 100ms you would set
   * delay = Duration(2, TimeUnit.SECONDS) and frequency = Duration(100, TimeUnit.MILLISECONDS)
   */
  def schedule(initialDelay: Duration, frequency: Duration)(f: ⇒ Unit): Cancellable

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that has to pass before the runnable is executed.
   */
  def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has to pass before the message is sent.
   */
  def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable

  /**
   * Schedules a function to be run once with a delay, i.e. a time period that has to pass before the function is run.
   */
  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable
}

trait Cancellable {
  /**
   * Cancels the underlying scheduled task.
   */
  def cancel(): Unit

  /**
   * Checks if the underlying scheduled task has been cancelled.
   */
  def isCancelled: Boolean
}