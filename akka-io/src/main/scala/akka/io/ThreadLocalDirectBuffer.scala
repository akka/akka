/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.ByteBuffer
import akka.actor.Actor

/**
 * Allows an actor to get a thread local direct buffer of a size defined in the
 * configuration of the actor system. An underlying assumption is that all of
 * the threads which call `getDirectBuffer` are owned by the actor system.
 */
trait ThreadLocalDirectBuffer { _: Actor ⇒
  def directBuffer(): ByteBuffer = {
    val result = ThreadLocalDirectBuffer.threadLocalBuffer.get()
    if (result == null) {
      val size = Tcp(context.system).Settings.DirectBufferSize
      val newBuffer = ByteBuffer.allocateDirect(size)
      ThreadLocalDirectBuffer.threadLocalBuffer.set(newBuffer)
      newBuffer
    } else {
      result.clear()
      result
    }
  }
}

object ThreadLocalDirectBuffer {
  private val threadLocalBuffer = new ThreadLocal[ByteBuffer]
}
