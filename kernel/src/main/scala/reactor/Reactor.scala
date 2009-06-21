/**
 * Copyright (C) 2009 Scalable Solutions.
 */

/**
 * Implements the Reactor pattern as defined in: [http://www.cs.wustl.edu/~schmidt/PDF/reactor-siemens.pdf].
 * See also this article: [http://today.java.net/cs/user/print/a/350].
 *
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */
package se.scalablesolutions.akka.kernel.reactor

import java.util.{LinkedList, Queue}
import kernel.util.HashCode

trait MessageHandler {
  def handle(message: MessageHandle)
}

trait MessageDispatcher {
  def messageQueue: MessageQueue
  def registerHandler(key: AnyRef, handler: MessageHandler)
  def unregisterHandler(key: AnyRef)
  def start
  def shutdown
}

trait MessageDemultiplexer {
  def select
  def acquireSelectedQueue: Queue[MessageHandle]
  def releaseSelectedQueue
  def wakeUp
}

class MessageHandle(val sender: AnyRef, val message: AnyRef, val future: CompletableFutureResult) {

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, sender)
    result = HashCode.hash(result, message)
    result = HashCode.hash(result, future)
    result
  }

  override def equals(that: Any): Boolean =
    that != null &&
    that.isInstanceOf[MessageHandle] &&
    that.asInstanceOf[MessageHandle].sender == sender &&
    that.asInstanceOf[MessageHandle].message == message &&
    that.asInstanceOf[MessageHandle].future == future
}

class MessageQueue {
  private[kernel] val queue: Queue[MessageHandle] = new LinkedList[MessageHandle]
  @volatile private var interrupted = false

  def append(handle: MessageHandle) = queue.synchronized {
    queue.offer(handle)
    queue.notifyAll
  }

  def prepend(handle: MessageHandle) = queue.synchronized {
    queue.add(handle)
    queue.notifyAll
  }
  
  def read(destination: Queue[MessageHandle]) = queue.synchronized {
    while (queue.isEmpty && !interrupted) queue.wait
    if (!interrupted) while (!queue.isEmpty) destination.offer(queue.remove)
    else interrupted = false
  }

  def interrupt = queue.synchronized {
    interrupted = true
    queue.notifyAll
  }
}
