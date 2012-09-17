/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.actor.mailbox.filebased.filequeue

import java.util.concurrent.atomic.AtomicLong

class Counter {
  private val value = new AtomicLong(0)

  def apply() = value.get
  def set(n: Long) = value.set(n)
  def incr() = value.addAndGet(1)
  def incr(n: Long) = value.addAndGet(n)
  def decr() = value.addAndGet(-1)
  def decr(n: Long) = value.addAndGet(-n)
  override def toString = value.get.toString
}
