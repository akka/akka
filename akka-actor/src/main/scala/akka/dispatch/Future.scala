/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.AkkaException
import akka.actor.Actor.spawn
import akka.routing.Dispatcher

import java.util.concurrent.locks.ReentrantLock
import akka.japi.Procedure
import java.util.concurrent. {ConcurrentLinkedQueue, TimeUnit}
import akka.actor.Actor
import annotation.tailrec
import java.util.concurrent.atomic. {AtomicBoolean, AtomicInteger}

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
   def future[T](timeout: Long,
                 dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher)
                (body: => T): Future[T] = {
    val f = new DefaultCompletableFuture[T](timeout)
    spawn({
      try { f completeWithResult body }
      catch { case e => f completeWithException e}
    })(dispatcher)
    f
  }

  /**
   * (Blocking!)
   */
  def awaitAll(futures: List[Future[_]]): Unit = futures.foreach(_.await)

  /**
   * Returns the First Future that is completed (blocking!)
   */
  def awaitOne(futures: List[Future[_]], timeout: Long = Long.MaxValue): Future[_] = firstCompletedOf(futures, timeout).await

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf(futures: Iterable[Future[_]], timeout: Long = Long.MaxValue): Future[_] = {
    val futureResult = new DefaultCompletableFuture[Any](timeout)
    val fun = (f: Future[_]) => futureResult completeWith f.asInstanceOf[Future[Any]]
    for(f <- futures) f onComplete fun
    futureResult
  }

  /**
   * Applies the supplied function to the specified collection of Futures after awaiting each future to be completed
   */
  def awaitMap[A,B](in: Traversable[Future[A]])(fun: (Future[A]) => B): Traversable[B] =
    in map { f => fun(f.await) }

  /**
   * Returns Future.resultOrException of the first completed of the 2 Futures provided (blocking!)
   */
  def awaitEither[T](f1: Future[T], f2: Future[T]): Option[T] = awaitOne(List(f1,f2)).asInstanceOf[Future[T]].resultOrException

  /**
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T,R](zero: R, timeout: Long = Actor.TIMEOUT)(futures: Iterable[Future[T]])(foldFun: (R, T) => R): Future[R] = {
    val result = new DefaultCompletableFuture[R](timeout)
    val results = new ConcurrentLinkedQueue[T]()
    val waitingFor = new AtomicInteger(futures.size)

    val aggregate = (f: Future[T]) => if (!result.isCompleted) { //TODO: This is an optimization, is it premature?
      if (f.exception.isDefined)
        result completeWithException f.exception.get
      else {
        results add f.result.get
        if (waitingFor.decrementAndGet == 0) { //Only one thread can get here
          try {
            val r = scala.collection.JavaConversions.asScalaIterable(results).foldLeft(zero)(foldFun)
            results.clear //Do not retain the values since someone can hold onto the Future for a long time
            result completeWithResult r
          } catch {
            case e: Exception => result completeWithException e
          }
        }
      }
    }

    futures foreach { _ onComplete aggregate }
    result
  }

  /**
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T, R >: T](futures: Iterable[Future[T]], timeout: Long = Actor.TIMEOUT)(op: (R,T) => T): Future[R] = {
    if (futures.isEmpty)
      throw new UnsupportedOperationException("empty reduce left")

    val result = new DefaultCompletableFuture[R](timeout)
    val seedFound = new AtomicBoolean(false)
    val seedFold = (f: Future[T]) => {
      if (seedFound.compareAndSet(false, true)){ //Only the first completed should trigger the fold
        if (f.exception.isDefined) result completeWithException f.exception.get //If the seed is a failure, we're done here
        else (fold[T,R](f.result.get, timeout)(futures.filterNot(_ eq f))(op)).onComplete(result.completeWith(_)) //Fold using the seed
      }
      () //Returns Unit
    }
    for(f <- futures) f onComplete seedFold //Attach the listener to the Futures
    result
  }


}

sealed trait Future[T] {
  def await : Future[T]

  def awaitBlocking : Future[T]

  def isCompleted: Boolean

  def isExpired: Boolean

  def timeoutInNanos: Long

  def result: Option[T]

  def exception: Option[Throwable]

  def onComplete(func: Future[T] => Unit): Future[T]

  /**
   *  Returns the current result, throws the exception is one has been raised, else returns None
   */
  def resultOrException: Option[T] = {
    val r = result
    if (r.isDefined) result
    else {
      val problem = exception
      if (problem.isDefined) throw problem.get
      else None
    }
  }

  /* Java API */
  def onComplete(proc: Procedure[Future[T]]): Future[T] = onComplete(f => proc(f))

  def map[O](f: (T) => O): Future[O] = {
    val wrapped = this
    new Future[O] {
      def await = { wrapped.await; this }
      def awaitBlocking = { wrapped.awaitBlocking; this }
      def isCompleted = wrapped.isCompleted
      def isExpired = wrapped.isExpired
      def timeoutInNanos = wrapped.timeoutInNanos
      def result: Option[O] = { wrapped.result map f }
      def exception: Option[Throwable] = wrapped.exception
      def onComplete(func: Future[O] => Unit): Future[O] = { wrapped.onComplete(_ => func(this)); this }
    }
  }
}

trait CompletableFuture[T] extends Future[T] {
  def completeWithResult(result: T)
  def completeWithException(exception: Throwable)
  def completeWith(other: Future[T]) {
    val result = other.result
    val exception = other.exception
    if (result.isDefined) completeWithResult(result.get)
    else if (exception.isDefined) completeWithException(exception.get)
    //else TODO how to handle this case?
  }
}

// Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
class DefaultCompletableFuture[T](timeout: Long) extends CompletableFuture[T] {
  import TimeUnit.{MILLISECONDS => TIME_UNIT}

  def this() = this(0)

  val timeoutInNanos = TIME_UNIT.toNanos(timeout)
  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _completed: Boolean = _
  private var _result: Option[T] = None
  private var _exception: Option[Throwable] = None
  private var _listeners: List[Future[T] => Unit] = Nil

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

  def completeWithResult(result: T) {
    val notifyTheseListeners = try {
      _lock.lock
      if (!_completed) {
        _completed = true
        _result = Some(result)
        val all = _listeners
        _listeners = Nil
        all
      } else Nil
    } finally {
      _signal.signalAll
      _lock.unlock
    }

    if (notifyTheseListeners.nonEmpty)
      notifyTheseListeners foreach notify
  }

  def completeWithException(exception: Throwable) {
    val notifyTheseListeners = try {
      _lock.lock
      if (!_completed) {
        _completed = true
        _exception = Some(exception)
        val all = _listeners
        _listeners = Nil
        all
      } else Nil
    } finally {
      _signal.signalAll
      _lock.unlock
    }

    if (notifyTheseListeners.nonEmpty)
      notifyTheseListeners foreach notify
  }

  def onComplete(func: Future[T] => Unit): CompletableFuture[T] = {
    val notifyNow = try {
      _lock.lock
      if (!_completed) {
        _listeners ::= func
        false
      }
      else
        true
    } finally {
      _lock.unlock
    }

    if (notifyNow)
      notify(func)

    this
  }

  private def notify(func: Future[T] => Unit) {
    func(this)
  }

  private def currentTimeInNanos: Long = TIME_UNIT.toNanos(System.currentTimeMillis)
}
