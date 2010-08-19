/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.AkkaException

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

class FutureTimeoutException(message: String) extends AkkaException(message)

object Futures {

  /**
   * Module with utility methods for working with Futures.
   * <pre>
   * val future = Futures.future(1000) {
   *  ... // do stuff
   * }
   * </pre>
   */
  def future[T](timeout: Long)(body: => T): Future[T] = {
    val promise = new DefaultCompletableFuture[T](timeout)
    try {
      promise completeWithResult body
    } catch {
      case e => promise completeWithException e
    }
    promise
  }

  def awaitAll(futures: List[Future[_]]): Unit = futures.foreach(_.await)

  def awaitOne(futures: List[Future[_]]): Future[_] = {
    var future: Option[Future[_]] = None
    do {
      future = futures.find(_.isCompleted)
    } while (future.isEmpty)
    future.get
  }

  /*
  def awaitEither[T](f1: Future[T], f2: Future[T]): Option[T] = {
    import Actor.Sender.Self
    import Actor.{spawn, actor}

    case class Result(res: Option[T])
    val handOff = new SynchronousQueue[Option[T]]
    spawn {
      try {
        println("f1 await")
        f1.await
        println("f1 offer")
        handOff.offer(f1.result)
      } catch {case _ => {}}
    }
    spawn {
      try {
        println("f2 await")
        f2.await
        println("f2 offer")
        println("f2 offer: " + f2.result)
        handOff.offer(f2.result)
      } catch {case _ => {}}
    }
    Thread.sleep(100)
    handOff.take
  }
*/
}

sealed trait Future[T] {
  def await : Future[T]
  def awaitBlocking : Future[T]
  def isCompleted: Boolean
  def isExpired: Boolean
  def timeoutInNanos: Long
  def result: Option[T]
  def exception: Option[Throwable]
}

trait CompletableFuture[T] extends Future[T] {
  def completeWithResult(result: T)
  def completeWithException(exception: Throwable)
}

// Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
class DefaultCompletableFuture[T](timeout: Long) extends CompletableFuture[T] {
  private val TIME_UNIT = TimeUnit.MILLISECONDS
  def this() = this(0)

  val timeoutInNanos = TIME_UNIT.toNanos(timeout)
  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _completed: Boolean = _
  private var _result: Option[T] = None
  private var _exception: Option[Throwable] = None

  def await = try {
    _lock.lock
    var wait = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)
    while (!_completed && wait > 0) {
      var start = currentTimeInNanos
      try {
        wait = _signal.awaitNanos(wait)
        if (wait <= 0) throw new FutureTimeoutException("Futures timed out after [" + timeout + "] milliseconds")
      } catch {
        case e: InterruptedException =>
          wait = wait - (currentTimeInNanos - start)
      }
    }
    this
  } finally {
    _lock.unlock
  }

  def awaitBlocking = try {
    _lock.lock
    while (!_completed) {
      _signal.await
    }
    this
  } finally {
    _lock.unlock
  }

  def isCompleted: Boolean = try {
    _lock.lock
    _completed
  } finally {
    _lock.unlock
  }

  def isExpired: Boolean = try {
    _lock.lock
    timeoutInNanos - (currentTimeInNanos - _startTimeInNanos) <= 0
  } finally {
    _lock.unlock
  }

  def result: Option[T] = try {
    _lock.lock
    _result
  } finally {
    _lock.unlock
  }

  def exception: Option[Throwable] = try {
    _lock.lock
    _exception
  } finally {
    _lock.unlock
  }

  def completeWithResult(result: T) = try {
    _lock.lock
    if (!_completed) {
      _completed = true
      _result = Some(result)
    }
  } finally {
    _signal.signalAll
    _lock.unlock
  }

  def completeWithException(exception: Throwable) = try {
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
