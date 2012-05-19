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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides circuit breaker functionality to provide stability when working with "dangerous" operations, e.g. calls to remote systems
 *
 * Transitions through three states:
 *  - In *Closed* state, calls pass through until the `maxFailures` count is reached.  This causes the circuit breaker to open.  Both exceptions and calls exceeding `callTimeout` are considered failures.
 * 	- In *Open* state, calls fail-fast with an exception.  After `resetTimeout`, circuit breaker transitions to half-open state.
 * 	- In *Half-Open* state, the first call will be allowed through, if it succeeds the circuit breaker will reset to closed state.  If it fails, the circuit breaker will re-open to open state.  All calls beyond the first that execute while the first is running will fail-fast with an exception.
 *
 *
 * @constructor Create a new circuit breaker
 * @param scheduler Reference to Akka scheduler
 * @param maxFailures Maximum number of failures before opening the circuit
 * @param callTimeout [[akka.util.Duration]] of time after which to consider a call a failure
 * @param resetTimeout [[akka.util.Duration]] of time after which to attempt to close the circuit
 */
class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: Duration, resetTimeout: Duration) {

  private val currentState: AtomicReference[CircuitBreakerState] = new AtomicReference(CircuitBreakerClosed)

  private val syncExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run
    def reportFailure(t: Throwable): Unit = {}
  }

  /**
   * Wraps invocations of asynchronous calls that need to be protected
   *
   * Calls are run in Future supplied by implicit [[akka.dispatch.ExecutionContext]]
   *
   *  @param body Call needing protected
   *  @tparam T return type from call
   *  @return [[akka.dispatch.Future]] containing the call result
   */
  def withCircuitBreaker[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
    currentState.get.invoke(body)
  }

  /**
   * Wraps invocations of synchronous calls that need to be protected
   *
   * Calls are run in caller's thread
   *
   * @param body Call needing protected
   * @tparam T return type from call
   * @return The result of the call
   */
  def withSyncCircuitBreaker[T](body: ⇒ T): T = {
    // execute the body in caller's thread
    implicit val executor = syncExecutionContext
    Await.result(withCircuitBreaker(
      try Promise.successful(body) catch { case NonFatal(t) ⇒ Promise.failed(t) }),
      Duration.Zero)
  }

  /**
   * Adds a callback to execute when circuit breaker opens
   *
   * Callback run in future supplied by implicit [[akka.dispatch.ExecutionContext]] which is present in the corresponding `withCircuitBreaker` call
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onOpen[T](callback: ⇒ T): CircuitBreaker = {
    CircuitBreakerOpen.addListener({ () ⇒ callback })
    this
  }

  /**
   * Adds a callback to execute when circuit breaker transitions to half-open
   *
   * Callback run in future supplied by implicit [[akka.dispatch.ExecutionContext]] which is present in the corresponding `withCircuitBreaker` call
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onHalfOpen[T](callback: ⇒ T): CircuitBreaker = {
    CircuitBreakerHalfOpen.addListener({ () ⇒ callback })
    this
  }

  /**
   * Adds a callback to execute when circuit breaker state closes
   *
   * Callback run in future supplied by implicit [[akka.dispatch.ExecutionContext]] which is present in the corresponding `withCircuitBreaker` call
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onClose[T](callback: ⇒ T): CircuitBreaker = {
    CircuitBreakerClosed.addListener({ () ⇒ callback })
    this
  }

  /**
   * Retrieves current failure count.
   *
   * @return count
   */
  def currentFailureCount: Int = CircuitBreakerClosed.failureCount.get

  /**
   * Implements consistent transition between states
   *
   * @param fromState State being transitioning from
   * @param toState State being transitioning from
   * @throws [[java.lang.IllegalStateException]] if an invalid transition is attempted
   */
  private def transition(fromState: CircuitBreakerState, toState: CircuitBreakerState)(implicit executor: ExecutionContext): Unit = {
    if (currentState.compareAndSet(fromState, toState))
      toState.enter()
    else
      throw new IllegalStateException("Illegal transition attempted from: " + fromState + " to " + toState)
  }

  /**
   * Trips breaker to an open state.  This is valid from Closed or Half-Open states.
   *
   * @param fromState State we're coming from (Closed or Half-Open)
   */
  private def tripBreaker(fromState: CircuitBreakerState)(implicit executor: ExecutionContext): Unit = {
    transition(fromState, CircuitBreakerOpen)
  }

  /**
   * Resets breaker to a closed state.  This is valid from an Half-Open state only.
   */
  private def resetBreaker()(implicit executor: ExecutionContext): Unit = {
    transition(CircuitBreakerHalfOpen, CircuitBreakerClosed)
  }

  /**
   * Attempts to reset breaker by transitioning to a half-open state.  This is valid from an Open state only.
   */
  private def attemptReset()(implicit executor: ExecutionContext): Unit = {
    transition(CircuitBreakerOpen, CircuitBreakerHalfOpen)
  }

  /**
   * Internal state abstraction
   */
  trait CircuitBreakerState {
    private val listeners = new CopyOnWriteArrayList[() ⇒ _]

    def addListener[T](listener: () ⇒ T) {
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
        case _ ⇒ callFails()
      } onSuccess {
        case _ ⇒
          if (deadline.isOverdue) callFails()
          else callSucceeds()
      }
    }

    def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T]

    def callSucceeds()(implicit executor: ExecutionContext): Unit

    def callFails()(implicit executor: ExecutionContext): Unit

    def enter()(implicit executor: ExecutionContext): Unit = {
      _enter()
      notifyTransitionListeners()
    }

    def _enter()(implicit executor: ExecutionContext): Unit
  }

  private object CircuitBreakerClosed extends CircuitBreakerState {
    val failureCount = new AtomicInteger(0)

    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      callThrough(body)
    }

    def callSucceeds()(implicit executor: ExecutionContext): Unit = failureCount.set(0)

    def callFails()(implicit executor: ExecutionContext): Unit = {
      if (failureCount.incrementAndGet() == maxFailures) tripBreaker(CircuitBreakerClosed)
    }

    def _enter()(implicit executor: ExecutionContext): Unit = {
      failureCount.set(0)
    }
  }

  private object CircuitBreakerHalfOpen extends CircuitBreakerState {

    private val singleAttempt = new AtomicBoolean(true)

    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      if (!singleAttempt.compareAndSet(true, false))
        throw new CircuitBreakerOpenException(Duration.Zero)
      callThrough(body)
    }

    override def callSucceeds()(implicit executor: ExecutionContext): Unit = resetBreaker()

    override def callFails()(implicit executor: ExecutionContext): Unit = tripBreaker(CircuitBreakerHalfOpen)

    override def _enter()(implicit executor: ExecutionContext): Unit = {
      singleAttempt.set(true)
    }

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

    override def _enter()(implicit executor: ExecutionContext): Unit = {
      openAsOf.set(System.currentTimeMillis)
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

