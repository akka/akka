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

trait MessageHandler {
  def handle(message: MessageHandle)
}

class GenericServerMessageHandler(val server: GenericServer) extends MessageHandler {
  def handle(handle: MessageHandle) = server.handle(handle.message, handle.future)
}

class MessageQueue {
  private val handles: Queue[MessageHandle] = new LinkedList[MessageHandle]
  @volatile private var interrupted = false

  def put(handle: MessageHandle) = handles.synchronized {
    handles.offer(handle)
    handles.notifyAll
  }

  def read(destination: Queue[MessageHandle]) = handles.synchronized {
    while (handles.isEmpty && !interrupted) handles.wait
    if (!interrupted) while (!handles.isEmpty) destination.offer(handles.remove)
    else interrupted = false
  }

  def interrupt = handles.synchronized {
    interrupted = true
    handles.notifyAll
  }
}
