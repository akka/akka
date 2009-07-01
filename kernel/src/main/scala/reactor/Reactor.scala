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

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadFactory
import java.util.{LinkedList, Queue}
import kernel.stm.Transaction
import kernel.util.{Logging, HashCode}
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

class MessageHandle(val sender: AnyRef,
                    val message: AnyRef,
                    val future: Option[CompletableFutureResult],
                    val tx: Option[Transaction]) {

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, sender)
    result = HashCode.hash(result, message)
    result = if (future.isDefined) HashCode.hash(result, future.get) else result
    result = if (tx.isDefined) HashCode.hash(result, tx.get.id) else result
    result
  }

  override def equals(that: Any): Boolean =
    that != null &&
    that.isInstanceOf[MessageHandle] &&
    that.asInstanceOf[MessageHandle].sender == sender &&
    that.asInstanceOf[MessageHandle].message == message &&
    that.asInstanceOf[MessageHandle].future.isDefined == future.isDefined &&
    that.asInstanceOf[MessageHandle].future.get == future.get &&
    that.asInstanceOf[MessageHandle].tx.isDefined == tx.isDefined &&
    that.asInstanceOf[MessageHandle].tx.get.id == tx.get.id
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
