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

trait Scheduler {
  /**
   * Schedules a message to be sent repeatedly with an initial delay and frequency.
   * E.g. if you would like a message to be sent immediately and thereafter every 500ms you would set
   * delay = Duration.Zero and frequency = Duration(500, TimeUnit.MILLISECONDS)
   */
  def schedule(receiver: ActorRef, message: Any, initialDelay: Duration, frequency: Duration): Cancellable

  /**
   * Schedules a function to be run repeatedly with an initial delay and a frequency.
   * E.g. if you would like the function to be run after 2 seconds and thereafter every 100ms you would set
   * delay = Duration(2, TimeUnit.SECONDS) and frequency = Duration(100, TimeUnit.MILLISECONDS)
   */
  def schedule(f: () ⇒ Unit, initialDelay: Duration, frequency: Duration): Cancellable

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that has to pass before the runnable is executed.
   */
  def scheduleOnce(runnable: Runnable, delay: Duration): Cancellable

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has to pass before the message is sent.
   */
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): Cancellable

  /**
   * Schedules a function to be run once with a delay, i.e. a time period that has to pass before the function is run.
   */
  def scheduleOnce(f: () ⇒ Unit, delay: Duration): Cancellable
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