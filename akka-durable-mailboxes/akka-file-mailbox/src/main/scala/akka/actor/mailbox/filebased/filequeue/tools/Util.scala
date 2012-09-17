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

package akka.actor.mailbox.filebased.filequeue.tools

object Util {
  val KILOBYTE = 1024L
  val MEGABYTE = 1024 * KILOBYTE
  val GIGABYTE = 1024 * MEGABYTE

  def bytesToHuman(bytes: Long, minDivisor: Long) = {
    if ((bytes == 0) && (minDivisor == 0)) {
      "0"
    } else {
      val divisor = if ((bytes >= GIGABYTE * 95 / 100) || (minDivisor == GIGABYTE)) {
        GIGABYTE
      } else if ((bytes >= MEGABYTE * 95 / 100) || (minDivisor == MEGABYTE)) {
        MEGABYTE
      } else {
        KILOBYTE
      }

      // add 1/2 when computing the dot, to force it to round up.
      var dot = ((bytes % divisor) * 20 + divisor) / (2 * divisor)
      var base = (bytes - (bytes % divisor)) / divisor
      if (dot >= 10) {
        base += 1
        dot -= 10
      }

      base.toString + (if (base < 100) ("." + dot) else "") + (divisor match {
        case KILOBYTE ⇒ "K"
        case MEGABYTE ⇒ "M"
        case GIGABYTE ⇒ "G"
        case _        ⇒ ""
      })
    }
  }
}
