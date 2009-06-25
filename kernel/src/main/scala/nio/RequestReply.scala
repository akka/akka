/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.util.concurrent.atomic.AtomicLong
import kernel.stm.Transaction
import kernel.util.HashCode

object IdFactory {
  private val id = new AtomicLong
  def nextId = id.getAndIncrement
}

@serializable class ProxyWrapper(val proxyName: String)

@serializable class RemoteRequest(val isActor: Boolean,
                                  val message: AnyRef,
                                  val method: String,
                                  val target: String,
                                  val tx: Option[Transaction],
                                  val isOneWay: Boolean,
                                  val isEscaped: Boolean) {
  private[RemoteRequest] var _id = IdFactory.nextId
  def id = _id

  override def toString: String = synchronized {
    "RemoteRequest[isActor: " + isActor + " | message: " + message + " | method: " + method + 
        " | target: " + target + " | tx: " + tx + " | isOneWay: " + isOneWay + "]"
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

  def newReplyWithMessage(message: AnyRef, tx: Option[Transaction]) = synchronized { new RemoteReply(true, id, message, null, tx) }

  def newReplyWithException(error: Throwable) = synchronized { new RemoteReply(false, id, null, error, None) }

  def cloneWithNewMessage(message: AnyRef, isEscaped: Boolean) = synchronized {
    val request = new RemoteRequest(isActor, message, method, target, tx, isOneWay, isEscaped)
    request._id = id
    request
  }
}

@serializable class RemoteReply(val successful: Boolean,
                                val id: Long,
                                val message: AnyRef,
                                val exception: Throwable,
                                val tx: Option[Transaction]) {
  override def toString: String = synchronized {
    "RemoteReply[successful: " + successful + " | id: " + id + " | message: " +
        message + " | exception: " + exception + " | tx: " + tx + "]"
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