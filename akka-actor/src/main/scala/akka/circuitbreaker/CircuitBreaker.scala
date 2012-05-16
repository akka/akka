/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.circuitbreaker

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

import akka.AkkaException
import akka.actor.Scheduler
import akka.dispatch.Future
import akka.dispatch.ExecutionContext
import akka.dispatch.Await
import akka.dispatch.Promise
import akka.util.Deadline
import akka.util.Duration
import akka.util.duration._
import akka.util.NonFatal

class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: Duration, resetTimeout: Duration) {

  private val currentState: AtomicReference[CircuitBreakerState] = new AtomicReference(CircuitBreakerClosed)

  private val syncExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run
    def reportFailure(t: Throwable): Unit = {}
  }

  def withCircuitBreaker[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
    currentState.get.invoke(body)
  }

  def withSyncCircuitBreaker[T](body: ⇒ T): T = {
    // execute the body in caller's thread
    implicit val executor = syncExecutionContext
    Await.result(withCircuitBreaker(
      try Promise.successful(body) catch { case NonFatal(t) ⇒ Promise.failed(t) }),
      Duration.Zero)
  }

  def onOpen(callback: ⇒ Unit): CircuitBreaker = {
    CircuitBreakerOpen.addListener({ () ⇒ callback })
    this
  }

  def onHalfOpen(callback: ⇒ Unit): CircuitBreaker = {
    CircuitBreakerHalfOpen.addListener({ () ⇒ callback })
    this
  }

  def onClose(callback: ⇒ Unit): CircuitBreaker = {
    CircuitBreakerClosed.addListener({ () ⇒ callback })
    this
  }

  def currentFailureCount: Int = CircuitBreakerClosed.failureCount.get

  private def tripBreaker()(implicit executor: ExecutionContext): Unit = {
    if (currentState.compareAndSet(CircuitBreakerClosed, CircuitBreakerOpen) ||
      currentState.compareAndSet(CircuitBreakerHalfOpen, CircuitBreakerOpen))
      CircuitBreakerOpen.enter()
  }

  private def resetBreaker()(implicit executor: ExecutionContext): Unit = {
    if (currentState.compareAndSet(CircuitBreakerHalfOpen, CircuitBreakerClosed))
      CircuitBreakerClosed.enter()
  }

  private def attemptReset()(implicit executor: ExecutionContext): Unit = {
    if (currentState.compareAndSet(CircuitBreakerOpen, CircuitBreakerHalfOpen))
      CircuitBreakerHalfOpen.enter()
  }

  trait CircuitBreakerState {
    private val listeners = new CopyOnWriteArrayList[() ⇒ _]

    def addListener(listener: () ⇒ Unit) {
      listeners add listener
    }

    private def hasListeners: Boolean = !listeners.isEmpty

    protected def notifyTransitionListeners()(implicit executor: ExecutionContext) {
      if (hasListeners) {
        val iterator = listeners.iterator
        while (iterator.hasNext) {
          val listener = iterator.next
          Future(listener())
        }
      }
    }

    def callThrough[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      val deadline = callTimeout.fromNow
      val bodyFuture = try body catch { case NonFatal(t) ⇒ Promise.failed(t) }
      bodyFuture onFailure {
        case t: Throwable ⇒ callFails()
      } onSuccess {
        case _ ⇒
          if (deadline.isOverdue) callFails()
          else callSucceeds()
      }
    }

    def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T]

    def callSucceeds()(implicit executor: ExecutionContext): Unit

    def callFails()(implicit executor: ExecutionContext): Unit

    def enter()(implicit executor: ExecutionContext): Unit

  }

  private object CircuitBreakerClosed extends CircuitBreakerState {
    val failureCount = new AtomicInteger(0)

    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      callThrough(body)
    }

    def callSucceeds()(implicit executor: ExecutionContext): Unit = failureCount.set(0)

    def callFails()(implicit executor: ExecutionContext): Unit = {
      val count = failureCount.incrementAndGet()
      if (count >= maxFailures) tripBreaker()
    }

    def enter()(implicit executor: ExecutionContext): Unit = {
      failureCount.set(0)
      notifyTransitionListeners()
    }
  }

  private object CircuitBreakerHalfOpen extends CircuitBreakerState {

    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      callThrough(body)
    }

    override def callSucceeds()(implicit executor: ExecutionContext): Unit = resetBreaker()

    override def callFails()(implicit executor: ExecutionContext): Unit = tripBreaker()

    override def enter()(implicit executor: ExecutionContext): Unit =
      notifyTransitionListeners()

  }

  private object CircuitBreakerOpen extends CircuitBreakerState {
    private val openAsOf = new AtomicLong

    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      Promise.failed[T](new CircuitBreakerOpenException(remainingTimeout().timeLeft))
    }

    private def remainingTimeout(): Deadline = openAsOf.get match {
      case 0L ⇒ Deadline.now
      case t  ⇒ (t.millis + resetTimeout).fromNow
    }

    override def callSucceeds()(implicit executor: ExecutionContext): Unit = {}

    override def callFails()(implicit executor: ExecutionContext): Unit = {}

    override def enter()(implicit executor: ExecutionContext): Unit = {
      openAsOf.set(System.currentTimeMillis)
      notifyTransitionListeners()
      scheduler.scheduleOnce(resetTimeout) {
        attemptReset()
      }
    }

  }

}

class CircuitBreakerOpenException(
  val remainingDuration: Duration,
  message: String = "Circuit Breaker is open; calls are failing fast")
  extends AkkaException(message)

