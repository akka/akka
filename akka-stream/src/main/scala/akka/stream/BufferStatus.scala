/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.annotation.DoNotInherit

class BufferStatus(val used: Int, val capacity: Int) {
  def getUsed() = used
  def getCapacity() = capacity
}

@DoNotInherit sealed abstract class GetBufferStatus

case object GetBufferStatus extends GetBufferStatus {
  /**
   * Java API: the singleton instance
   */
  def getInstance(): GetBufferStatus = this
}
