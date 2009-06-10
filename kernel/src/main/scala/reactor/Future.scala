/**
 * Copyright (C) 2009 Scalable Solutions.
 */

/**
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */
package se.scalablesolutions.akka.kernel.reactor

import java.util.concurrent.locks.{Lock, Condition, ReentrantLock}
import java.util.concurrent.TimeUnit

class FutureTimeoutException(message: String) extends RuntimeException(message)

sealed trait FutureResult {
  def await_?
  def await_!
  def isCompleted: Boolean
  def isExpired: Boolean
  def timeoutInNanos: Long
  def result: Option[AnyRef]
  def exception: Option[Throwable]
}

trait CompletableFutureResult extends FutureResult {
  def completeWithResult(result: AnyRef)
  def completeWithException(exception: Throwable)
}

class DefaultCompletableFutureResult(timeout: Long) extends CompletableFutureResult {
  private val TIME_UNIT = TimeUnit.MILLISECONDS
  def this() = this(0)

  val timeoutInNanos = TIME_UNIT.toNanos(timeout)
  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _completed: Boolean = _
  private var _result: Option[AnyRef] = None
  private var _exception: Option[Throwable] = None
  
  override def await_? = try {
    _lock.lock
    var wait = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)
    while (!_completed && wait > 0) {
      var start = currentTimeInNanos
      try {
        wait = _signal.awaitNanos(wait)
        if (wait <= 0) throw new FutureTimeoutException("Future timed out after [" + timeout + "] milliseconds") 
      } catch {
        case e: InterruptedException =>
          wait = wait - (currentTimeInNanos - start)
      }
    }
  } finally {
    _lock.unlock
  }

  override def await_! = try {
    _lock.lock
    while (!_completed) {
      _signal.await
    }
  } finally {
    _lock.unlock
  }

  override def isCompleted: Boolean = try {
    _lock.lock
    _completed
  } finally {
    _lock.unlock
  }

  override def isExpired: Boolean = try {
    _lock.lock
    timeoutInNanos - (currentTimeInNanos - _startTimeInNanos) <= 0
  } finally {
    _lock.unlock
  }

  override def result: Option[AnyRef] = try {
    _lock.lock
    _result
  } finally {
    _lock.unlock
  }

  override def exception: Option[Throwable] = try {
    _lock.lock
    _exception
  } finally {
    _lock.unlock
  }

  override def completeWithResult(result: AnyRef) = try {
    _lock.lock
    if (!_completed) {
      _completed = true
      _result = Some(result)
    }
  } finally {
    _signal.signalAll
    _lock.unlock
  }

  override def completeWithException(exception: Throwable) = try {
    _lock.lock
    if (!_completed) {
      _completed = true
      _exception = Some(exception)
    }

  } finally {
    _signal.signalAll
    _lock.unlock
  }

  private def currentTimeInNanos: Long = TIME_UNIT.toNanos(System.currentTimeMillis)
}

class NullFutureResult extends CompletableFutureResult {
  override def completeWithResult(result: AnyRef) = {}
  override def completeWithException(exception: Throwable) = {}
  override def await_? = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def await_! = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def isCompleted: Boolean = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def isExpired: Boolean = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def timeoutInNanos: Long = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def result: Option[AnyRef] = None
  override def exception: Option[Throwable] = None
}
