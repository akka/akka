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

import java.nio.{ ByteBuffer, ByteOrder }

case class QItem(addTime: Long, expiry: Long, data: Array[Byte], var xid: Int) {
  def pack(): Array[Byte] = {
    val bytes = new Array[Byte](data.length + 16)
    val buffer = ByteBuffer.wrap(bytes)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(addTime)
    buffer.putLong(expiry)
    buffer.put(data)
    bytes
  }
}

object QItem {
  def unpack(data: Array[Byte]): QItem = {
    val buffer = ByteBuffer.wrap(data)
    val bytes = new Array[Byte](data.length - 16)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val addTime = buffer.getLong
    val expiry = buffer.getLong
    buffer.get(bytes)
    QItem(addTime, expiry, bytes, 0)
  }

  def unpackOldAdd(data: Array[Byte]): QItem = {
    val buffer = ByteBuffer.wrap(data)
    val bytes = new Array[Byte](data.length - 4)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val expiry = buffer.getInt
    buffer.get(bytes)
    QItem(System.currentTimeMillis, if (expiry == 0) 0 else expiry * 1000, bytes, 0)
  }
}
