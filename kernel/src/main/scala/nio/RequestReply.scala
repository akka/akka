/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.util.concurrent.atomic.AtomicLong
import kernel.util.HashCode

object IdFactory {
  private val id = new AtomicLong
  def nextId = id.getAndIncrement
}

@serializable class ProxyWrapper(val proxyName: String)

@serializable class RemoteRequest(val isActor: Boolean, val message: AnyRef, val method: String, val target: String, val isOneWay: Boolean) {
  private[RemoteRequest] var _id = IdFactory.nextId
  def id = _id

  override def toString: String = synchronized {
    "RemoteRequest[isActor: " + isActor + " | message: " + message + " | method: " + method + " | target: " + target + " | isOneWay: " + isOneWay + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, isActor)
    result = HashCode.hash(result, message)
    result = HashCode.hash(result, method)
    result = HashCode.hash(result, target)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[RemoteRequest] &&
    that.asInstanceOf[RemoteRequest].isActor == isActor &&
    that.asInstanceOf[RemoteRequest].message == message &&
    that.asInstanceOf[RemoteRequest].method == method &&
    that.asInstanceOf[RemoteRequest].target == target
  }

  def newReplyWithMessage(message: AnyRef) = synchronized { new RemoteReply(true, id, message, null) }

  def newReplyWithException(error: Throwable) = synchronized { new RemoteReply(false, id, null, error) }

  def cloneWithNewMessage(message: AnyRef) = synchronized {
    val request = new RemoteRequest(isActor, message, method, target, isOneWay)
    request._id = id
    request
  }
}

@serializable class RemoteReply(val successful: Boolean, val id: Long, val message: AnyRef, val exception: Throwable) {
  override def toString: String = synchronized {
    "RemoteReply[successful: " + successful + " | id: " + id + " | message: " + message + " | exception: " + exception + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, successful)
    result = HashCode.hash(result, id)
    result = HashCode.hash(result, message)
    result = HashCode.hash(result, exception)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[RemoteReply] &&
    that.asInstanceOf[RemoteReply].successful == successful &&
    that.asInstanceOf[RemoteReply].id == id &&
    that.asInstanceOf[RemoteReply].message == message &&
    that.asInstanceOf[RemoteReply].exception == exception
  }
}