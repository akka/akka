/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.{LinkedList, Queue, List}
import java.util.HashMap

import se.scalablesolutions.akka.actor.{ActorMessageInvoker, Actor}

abstract class AbstractReactorBasedEventDrivenDispatcher(val name: String) extends MessageDispatcher {
  @volatile protected var active: Boolean = false
  protected val queue = new ReactiveMessageQueue(name)
  protected val messageInvokers = new HashMap[AnyRef, MessageInvoker]
  protected var selectorThread: Thread = _
  protected val guard = new Object

  def dispatch(invocation: MessageInvocation) = queue.append(invocation) 

  override def register(actor: Actor) = synchronized {
    messageInvokers.put(actor, new ActorMessageInvoker(actor))
    super.register(actor)
  }

  override def unregister(actor: Actor) = synchronized {
    messageInvokers.remove(actor)
    super.unregister(actor)
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
