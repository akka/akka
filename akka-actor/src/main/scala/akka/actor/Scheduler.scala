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
  def schedule(receiver: ActorRef, message: Any, initialDelay: Duration, delay: Duration): Cancellable
  def schedule(f: () ⇒ Unit, initialDelay: Duration, delay: Duration): Cancellable
  def scheduleOnce(runnable: Runnable, delay: Duration): Cancellable
  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): Cancellable
  def scheduleOnce(f: () ⇒ Unit, delay: Duration): Cancellable
}

trait Cancellable {
  def cancel(): Unit
  def isCancelled: Boolean
}