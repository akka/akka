/**
 * Copyright (C) 2009 Scalable Solutions.
 */

/**
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */

package se.scalablesolutions.akka.kernel.reactor

import java.util.concurrent.locks.{Lock, Condition, ReentrantLock}
import java.util.concurrent.TimeUnit

sealed trait FutureResult {
  def await
  def isCompleted: Boolean
  def isExpired: Boolean
  def timeoutInNanos: Long
  def result: AnyRef
  def exception: Exception
}

trait CompletableFutureResult extends FutureResult {
  def completeWithResult(result: AnyRef)
  def completeWithException(exception: Exception)
}

class CoreFutureResult(val timeoutInNanos: Long) extends CompletableFutureResult {

  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _completed: Boolean = _
  private var _result: AnyRef = _
  private var _exception: Exception = _
  
  override def await = try {
    _lock.lock
    var wait = timeoutInNanos - currentTimeInNanos - _startTimeInNanos
    while (!_completed && wait > 0) {
      var start = currentTimeInNanos
      try {
        wait = _signal.awaitNanos(wait)
      } catch {
        case e: InterruptedException =>
          wait = wait - currentTimeInNanos - start
      }
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
    timeoutInNanos - currentTimeInNanos - _startTimeInNanos <= 0
  } finally {
    _lock.unlock
  }

  override def result: AnyRef = try {
    _lock.lock
    _result
  } finally {
    _lock.unlock
  }

  override def exception: Exception = try {
    _lock.lock
    _exception
  } finally {
    _lock.unlock
  }

  override def completeWithResult(result: AnyRef) = try {
    _lock.lock
    if (!_completed) {
      _completed = true
      _result = result
    }

  } finally {
    _signal.signalAll
    _lock.unlock
  }

  override def completeWithException(exception: Exception) = try {
    _lock.lock
    if (!_completed) {
      _completed = true
      _exception = exception
    }

  } finally {
    _signal.signalAll
    _lock.unlock
  }

  private def currentTimeInNanos: Long = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis)
}

class NullFutureResult extends CompletableFutureResult {
  override def completeWithResult(result: AnyRef) = {}
  override def completeWithException(exception: Exception) = {}
  override def await: Unit = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def isCompleted: Boolean = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def isExpired: Boolean = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def timeoutInNanos: Long = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def result: AnyRef = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
  override def exception: Exception = throw new UnsupportedOperationException("Not implemented for NullFutureResult")
}
