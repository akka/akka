/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.reactor

import java.util.concurrent.{ConcurrentMap, ConcurrentHashMap}

trait MessageDispatcherBase extends MessageDispatcher {
  val messageQueue = new MessageQueue

  protected val messageHandlers = new ConcurrentHashMap[AnyRef, MessageHandler]
  protected var selectorThread: Thread = _
  @volatile protected var active: Boolean = false
  protected val guard = new Object

  def registerHandler(key: AnyRef, handler: MessageHandler) = guard.synchronized {
    messageHandlers.put(key, handler)
  }

  def unregisterHandler(key: AnyRef) = guard.synchronized {
    messageHandlers.remove(key)
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
