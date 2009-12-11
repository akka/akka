/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.dispatch

import java.util.{LinkedList, Queue, List}
import java.util.HashMap

abstract class AbstractReactorBasedEventDrivenDispatcher(val name: String) extends MessageDispatcher {
  protected val queue = new ReactiveMessageQueue(name)
  @volatile protected var active: Boolean = false
  protected val messageHandlers = new HashMap[AnyRef, MessageInvoker]
  protected var selectorThread: Thread = _
  protected val guard = new Object

  def dispatch(invocation: MessageInvocation) = queue.append(invocation) 

  def registerHandler(key: AnyRef, handler: MessageInvoker) = guard.synchronized {
    messageHandlers.put(key, handler)
  }

  def unregisterHandler(key: AnyRef) = guard.synchronized {
    messageHandlers.remove(key)
  }

  def canBeShutDown: Boolean = guard.synchronized {
    messageHandlers.isEmpty
  }
  
  def shutdown = if (active) {
    active = false
    selectorThread.interrupt
    doShutdown
  }

  /**
   * Subclass callback. Override if additional shutdown behavior is needed.
   */
  protected def doShutdown = {}
}

class ReactiveMessageQueue(name: String) extends MessageQueue {
  private[akka] val queue: Queue[MessageInvocation] = new LinkedList[MessageInvocation]
  @volatile private var interrupted = false

  def append(handle: MessageInvocation) = queue.synchronized {
    queue.offer(handle)
    queue.notifyAll
  }

  def prepend(handle: MessageInvocation) = queue.synchronized {
    queue.add(handle)
    queue.notifyAll
  }

  def read(destination: List[MessageInvocation]) = queue.synchronized {
    while (queue.isEmpty && !interrupted) queue.wait
    if (!interrupted) while (!queue.isEmpty) destination.add(queue.remove)
    else interrupted = false
  }

  def interrupt = queue.synchronized {
    interrupted = true
    queue.notifyAll
  }
}
