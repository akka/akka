/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.{LinkedList, Queue, List}
import java.util.HashMap

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

abstract class AbstractReactorBasedEventDrivenDispatcher(val name: String) extends MessageDispatcher {
  @volatile protected var active: Boolean = false
  protected val queue = new ReactiveMessageQueue(name)
  protected val messageInvokers = new HashMap[ActorRef, ActorRef]
  protected var selectorThread: Thread = _
  protected val guard = new Object

  def dispatch(invocation: MessageInvocation) = queue.append(invocation)

  override def register(actorRef: ActorRef) = synchronized {
    messageInvokers.put(actorRef, actorRef)
    super.register(actorRef)
  }

  override def unregister(actorRef: ActorRef) = synchronized {
    messageInvokers.remove(actorRef)
    super.unregister(actorRef)
  }

  def shutdown = if (active) {
    log.debug("Shutting down %s", toString)
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
