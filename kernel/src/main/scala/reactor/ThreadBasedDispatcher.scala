/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.reactor

import com.twitter.service.Stats

import java.util.concurrent.LinkedBlockingQueue
import java.util.Queue

import kernel.actor.{Actor, ActorMessageInvoker}
import kernel.management.Management

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher private[kernel] (val name: String, val messageHandler: MessageInvoker) extends MessageDispatcher {
  def this(actor: Actor) = this(actor.getClass.getName, new ActorMessageInvoker(actor))

  val NR_OF_PROCESSED_MESSAGES = Stats.getCounter("NrOfProcessedMessages_" + name)

  private val queue = new BlockingMessageQueue(name)
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  def messageQueue = queue
  
  def start = if (!active) {
    active = true
    selectorThread = new Thread {
      override def run = {
        while (active) {
          try {
            if (Management.RECORD_STATS) NR_OF_PROCESSED_MESSAGES.incr
            messageHandler.invoke(queue.take)
          } catch { case e: InterruptedException => active = false }
        }
      }
    }
    selectorThread.start
  }
                       
  def shutdown = if (active) {
    active = false
    selectorThread.interrupt
  }
  
  def registerHandler(key: AnyRef, handler: MessageInvoker) = throw new UnsupportedOperationException
  def unregisterHandler(key: AnyRef) = throw new UnsupportedOperationException
}

class BlockingMessageQueue(name: String) extends MessageQueue {
  if (Management.RECORD_STATS) {
    Stats.makeGauge("SizeOfBlockingQueue_" + name) {
      queue.size.toDouble
    }
  }

  // FIXME: configure the LBQ
  private val queue = new LinkedBlockingQueue[MessageInvocation]
  def append(handle: MessageInvocation) = queue.put(handle)
  def prepend(handle: MessageInvocation) = queue.add(handle) // FIXME is add prepend???
  def take: MessageInvocation = queue.take
  def read(destination: Queue[MessageInvocation]) = throw new UnsupportedOperationException
  def interrupt = throw new UnsupportedOperationException
}
