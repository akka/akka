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
                                  val timeout: Long,
                                  val tx: Option[Transaction],
                                  val isOneWay: Boolean,
                                  val isEscaped: Boolean,
                                  val supervisorUuid: Option[String]) {
  private[RemoteRequest] var _id = IdFactory.nextId
  def id = _id

  override def toString: String = synchronized {
    "RemoteRequest[isActor: " + isActor + " | message: " + message + " | timeout: " + timeout + " | method: " + method + 
    " | target: " + target + " | tx: " + tx + " | isOneWay: " + isOneWay + " | supervisorUuid: " + supervisorUuid + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, isActor)
    result = HashCode.hash(result, message)
    result = HashCode.hash(result, method)
    result = HashCode.hash(result, target)
    result = HashCode.hash(result, timeout)
    result = HashCode.hash(result, isOneWay)
    result = HashCode.hash(result, isEscaped)
    result = if (tx.isDefined) HashCode.hash(result, tx.get) else result
    result = if (supervisorUuid.isDefined) HashCode.hash(result, supervisorUuid.get) else result
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[RemoteRequest] &&
    that.asInstanceOf[RemoteRequest].isActor == isActor &&
    that.asInstanceOf[RemoteRequest].message == message &&
    that.asInstanceOf[RemoteRequest].method == method &&
    that.asInstanceOf[RemoteRequest].target == target &&
    that.asInstanceOf[RemoteRequest].timeout == timeout &&
    that.asInstanceOf[RemoteRequest].isOneWay == isOneWay &&
    that.asInstanceOf[RemoteRequest].isEscaped == isEscaped &&
    that.asInstanceOf[RemoteRequest].tx.isDefined == tx.isDefined &&
    that.asInstanceOf[RemoteRequest].tx.get == tx.get &&
    that.asInstanceOf[RemoteRequest].supervisorUuid.isDefined == supervisorUuid.isDefined &&
    that.asInstanceOf[RemoteRequest].supervisorUuid.get == supervisorUuid.get
  }

  def newReplyWithMessage(message: AnyRef, tx: Option[Transaction]) = synchronized { new RemoteReply(true, id, message, null, tx, supervisorUuid) }

  def newReplyWithException(error: Throwable) = synchronized { new RemoteReply(false, id, null, error, None, supervisorUuid) }

  def cloneWithNewMessage(message: AnyRef, isEscaped: Boolean) = synchronized {
    val request = new RemoteRequest(isActor, message, method, target, timeout, tx, isOneWay, isEscaped, supervisorUuid)
    request._id = id
    request
  }
}

@serializable class RemoteReply(val successful: Boolean,
                                val id: Long,
                                val message: AnyRef,
                                val exception: Throwable,
                                val tx: Option[Transaction],
                                val supervisorUuid: Option[String]) {
  override def toString: String = synchronized {
    "RemoteReply[successful: " + successful + " | id: " + id + " | message: " + message +
    " | exception: " + exception + " | tx: " + tx + " | supervisorUuid: " + supervisorUuid + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, successful)
    result = HashCode.hash(result, id)
    result = HashCode.hash(result, message)
    result = HashCode.hash(result, exception)
    result = if (tx.isDefined) HashCode.hash(result, tx.get) else result
    result = if (supervisorUuid.isDefined) HashCode.hash(result, supervisorUuid.get) else result
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[RemoteReply] &&
    that.asInstanceOf[RemoteReply].successful == successful &&
    that.asInstanceOf[RemoteReply].id == id &&
    that.asInstanceOf[RemoteReply].message == message &&
    that.asInstanceOf[RemoteReply].exception == exception &&
    that.asInstanceOf[RemoteRequest].tx.isDefined == tx.isDefined &&
    that.asInstanceOf[RemoteRequest].tx.get == tx.get &&
    that.asInstanceOf[RemoteRequest].supervisorUuid.isDefined == supervisorUuid.isDefined &&
    that.asInstanceOf[RemoteRequest].supervisorUuid.get == supervisorUuid.get
  }
}