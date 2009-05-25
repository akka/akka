/**
 * Copyright (C) 2009 Scalable Solutions.
 */

/**
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */

package se.scalablesolutions.akka.kernel.reactor

import java.util.{LinkedList, Queue}

import kernel.reactor.CompletableFutureResult

trait MessageDispatcher {
  def registerHandler(key: AnyRef, handler: MessageHandler)
  def unregisterHandler(key: AnyRef)
  def dispatch(messageQueue: MessageQueue)
  def shutdown
}

trait MessageDemultiplexer {
  def select
  def acquireSelectedQueue: Queue[MessageHandle]
  def releaseSelectedQueue
  def wakeUp
}

class MessageHandle(val key: AnyRef, val message: AnyRef, val future: CompletableFutureResult) {
  override def equals(obj: Any): Boolean = {
    // FIXME: implement equals
    true
  }

  override def hashCode: Int = {
    // FIXME: implement hashCode
    1
  }
}

trait MessageHandler {
  def handle(message: MessageHandle)
}


class MessageQueue {
  private val handles: Queue[MessageHandle] = new LinkedList[MessageHandle]
  @volatile private var interrupted = false

  def put(handle: MessageHandle) = handles.synchronized {
    handles.offer(handle)
    handles.notifyAll
  }

  def read(destination: Queue[MessageHandle]) = handles.synchronized {
    while (handles.isEmpty && !interrupted) {
      handles.wait
    }
    if (!interrupted) {
      while (!handles.isEmpty) {
        destination.offer(handles.remove)
      }
    } else {
      interrupted = false
    }
  }

  def interrupt = handles.synchronized {
    interrupted = true
    handles.notifyAll
  }
}
