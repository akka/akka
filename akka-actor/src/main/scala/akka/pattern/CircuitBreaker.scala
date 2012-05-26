/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicReference, AtomicBoolean }
import java.util.concurrent.CopyOnWriteArrayList
import akka.AkkaException
import akka.actor.Scheduler
import akka.dispatch.{ Future, ExecutionContext, Await, Promise }
import akka.util.{ Deadline, Duration, NonFatal }
import akka.util.duration._
import util.control.NoStackTrace

/**
 * Companion object containing reusable functionality
 */
private object CircuitBreaker {

  /**
   * Synchronous execution context to run in caller's thread - for consistency of interface between async and sync
   */
  val syncExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run

    def reportFailure(t: Throwable): Unit = {}
  }

}

/**
 * Provides circuit breaker functionality to provide stability when working with "dangerous" operations, e.g. calls to
 * remote systems
 *
 * Transitions through three states:
 * - In *Closed* state, calls pass through until the `maxFailures` count is reached.  This causes the circuit breaker
 * to open.  Both exceptions and calls exceeding `callTimeout` are considered failures.
 * - In *Open* state, calls fail-fast with an exception.  After `resetTimeout`, circuit breaker transitions to
 * half-open state.
 * - In *Half-Open* state, the first call will be allowed through, if it succeeds the circuit breaker will reset to
 * closed state.  If it fails, the circuit breaker will re-open to open state.  All calls beyond the first that
 * execute while the first is running will fail-fast with an exception.
 *
 *
 * @constructor Create a new circuit breaker
 * @param scheduler Reference to Akka scheduler
 * @param maxFailures Maximum number of failures before opening the circuit
 * @param callTimeout [[akka.util.Duration]] of time after which to consider a call a failure
 * @param resetTimeout [[akka.util.Duration]] of time after which to attempt to close the circuit
 */
class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: Duration, resetTimeout: Duration) {

  /**
   * Holds reference to current state of CircuitBreaker
   */
  private val currentState: AtomicReference[State] = new AtomicReference(Closed)

  /**
   * Wraps invocations of asynchronous calls that need to be protected
   *
   * @param body Call needing protected
   * @param executor ExecutionContext used for state transition listeners
   * @tparam T return type from call
   * @return [[akka.dispatch.Future]] containing the call result
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
    import CircuitBreaker.syncExecutionContext

    // execute the body in caller's thread
    implicit val executor = syncExecutionContext
    Await.result(withCircuitBreaker(
      try Promise.successful(body) catch {
        case NonFatal(t) ⇒ Promise.failed(t)
      }),
      Duration.Zero)
  }

  /**
   * Adds a callback to execute when circuit breaker opens
   *
   * For asynchronous circuit breaker, the callback is run asynchrnonously in implicitly supplied [[akka.dispatch.ExecutionContext]]
   * to `withCircuitBreaker`
   *
   * For synchronous circuit breaker, the callback is run in the caller's thread invoking `withSyncCircuitBreaker`.  The
   * callback will be invoked before `withSyncCircuitBreaker` returns
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onOpen[T](callback: ⇒ T): CircuitBreaker = {
    Open.addListener(() ⇒ callback)
    this
  }

  /**
   * Adds a callback to execute when circuit breaker transitions to half-open
   *
   * For asynchronous circuit breaker, the callback is run asynchrnonously in implicitly supplied [[akka.dispatch.ExecutionContext]]
   * to `withCircuitBreaker`
   *
   * For synchronous circuit breaker, the callback is run in the caller's thread invoking `withSyncCircuitBreaker`.  The
   * callback will be invoked before `withSyncCircuitBreaker` returns
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onHalfOpen[T](callback: ⇒ T): CircuitBreaker = {
    HalfOpen.addListener(() ⇒ callback)
    this
  }

  /**
   * Adds a callback to execute when circuit breaker state closes
   *
   * For asynchronous circuit breaker, the callback is run asynchrnonously in implicitly supplied [[akka.dispatch.ExecutionContext]]
   * to `withCircuitBreaker`
   *
   * For synchronous circuit breaker, the callback is run in the caller's thread invoking `withSyncCircuitBreaker`.  The
   * callback will be invoked before `withSyncCircuitBreaker` returns
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onClose[T](callback: ⇒ T): CircuitBreaker = {
    Closed.addListener(() ⇒ callback)
    this
  }

  /**
   * Retrieves current failure count.
   *
   * @return count
   */
  def currentFailureCount: Int = Closed.get

  /**
   * Implements consistent transition between states
   *
   * @param fromState State being transitioning from
   * @param toState State being transitioning from
   * @param executor Used for execution of state transition listeners
   * @throws IllegalStateException if an invalid transition is attempted
   */
  private def transition(fromState: State, toState: State)(implicit executor: ExecutionContext): Unit = {
    if (currentState.compareAndSet(fromState, toState))
      toState.enter()
    else
      throw new IllegalStateException("Illegal transition attempted from: " + fromState + " to " + toState)
  }

  /**
   * Trips breaker to an open state.  This is valid from Closed or Half-Open states.
   *
   * @param fromState State we're coming from (Closed or Half-Open)
   * @param executor Used for execution of state transition listeners
   */
  private def tripBreaker(fromState: State)(implicit executor: ExecutionContext): Unit = {
    transition(fromState, Open)
  }

  /**
   * Resets breaker to a closed state.  This is valid from an Half-Open state only.
   *
   * @param executor Used for execution of state transition listeners
   */
  private def resetBreaker()(implicit executor: ExecutionContext): Unit = {
    transition(HalfOpen, Closed)
  }

  /**
   * Attempts to reset breaker by transitioning to a half-open state.  This is valid from an Open state only.
   *
   * @param executor Used for execution of state transition listeners
   */
  private def attemptReset()(implicit executor: ExecutionContext): Unit = {
    transition(Open, HalfOpen)
  }

  /**
   * Internal state abstraction
   */
  private sealed trait State {
    private val listeners = new CopyOnWriteArrayList[() ⇒ _]

    /**
     * Add a listener function which is invoked on state entry
     *
     * @param listener listener implementation
     * @tparam T return type of listener, not used - but supplied for type inference purposes
     */
    def addListener[T](listener: () ⇒ T) {
      listeners add listener
    }

    /**
     * Test for whether listeners exist
     *
     * @return whether listeners exist
     */
    private def hasListeners: Boolean = !listeners.isEmpty

    /**
     * Notifies the listeners of the transition event via a Future executed in implicit parameter ExecutionContext
     *
     * @param executor ExecutionContext used for execution of listeners
     * @return Promise which executes listener in supplied [[akka.dispatch.ExecutionContext]]
     */
    protected def notifyTransitionListeners()(implicit executor: ExecutionContext) {
      if (hasListeners) {
        val iterator = listeners.iterator
        while (iterator.hasNext) {
          val listener = iterator.next
          Future(listener())
        }
      }
    }

    /**
     * Shared implementation of call across all states.  Thrown exception or execution of the call beyond the allowed
     * call timeout is counted as a failed call, otherwise a successful call
     *
     * @param body Implementation of the call
     * @param executor ExecutionContext used for execution of the passed Future
     * @tparam T Return type of the call's implementation
     * @return Future containing the result of the call
     */
    def callThrough[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      val deadline = callTimeout.fromNow
      val bodyFuture = try body catch {
        case NonFatal(t) ⇒ Promise.failed(t)
      }
      bodyFuture onFailure {
        case _ ⇒ callFails()
      } onSuccess {
        case _ ⇒
          if (deadline.isOverdue) callFails()
          else callSucceeds()
      }
    }

    /**
     * Abstract entry point for all states
     *
     * @param body Implementation of the call that needs protected
     * @param executor ExecutionContext used for listener execution
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T]

    /**
     * Invoked when call succeeds
     *
     * @param executor ExecutionContext used for listener execution
     */
    def callSucceeds()(implicit executor: ExecutionContext): Unit

    /**
     * Invoked when call fails
     *
     * @param executor ExecutionContext used for listener execution
     */
    def callFails()(implicit executor: ExecutionContext): Unit

    /**
     * Invoked on the transitioned-to state during transition.  Notifies listeners after invoking subclass template
     * method _enter
     *
     * @param executor ExecutionContext used for listener execution
     */
    def enter()(implicit executor: ExecutionContext): Unit = {
      _enter()
      notifyTransitionListeners()
    }

    /**
     * Template method for concrete traits
     *
     * @param executor ExecutionContext used for listener execution
     */
    def _enter()(implicit executor: ExecutionContext): Unit
  }

  /**
   * Concrete implementation of Closed state
   */
  private object Closed extends AtomicInteger with State {

    /**
     * Implementation of invoke, which simply attempts the call
     *
     * @param body Implementation of the call that needs protected
     * @param executor ExecutionContext used for listener execution
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      callThrough(body)
    }

    /**
     * On successful call, the failure count is reset to 0
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    def callSucceeds()(implicit executor: ExecutionContext): Unit = set(0)

    /**
     * On failed call, the failure count is incremented.  The count is checked against the configured maxFailures, and
     * the breaker is tripped if we have reached maxFailures.
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    def callFails()(implicit executor: ExecutionContext): Unit = {
      if (incrementAndGet() == maxFailures) tripBreaker(Closed)
    }

    /**
     * On entry of this state, failure count is reset.
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    def _enter()(implicit executor: ExecutionContext): Unit = {
      set(0)
    }
  }

  /**
   * Concrete implementation of half-open state
   */
  private object HalfOpen extends AtomicBoolean(true) with State {

    /**
     * Allows a single call through, during which all other callers fail-fast.  If the call fails, the breaker reopens.
     * If the call succeeds the breaker closes.
     *
     * @param body Implementation of the call that needs protected
     * @param executor ExecutionContext used for listener execution
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      if (!compareAndSet(true, false))
        throw new CircuitBreakerOpenException(Duration.Zero)
      callThrough(body)
    }

    /**
     * Reset breaker on successful call.
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    override def callSucceeds()(implicit executor: ExecutionContext): Unit = resetBreaker()

    /**
     * Reopen breaker on failed call.
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    override def callFails()(implicit executor: ExecutionContext): Unit = tripBreaker(HalfOpen)

    /**
     * On entry, guard should be reset for that first call to get in
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    override def _enter()(implicit executor: ExecutionContext): Unit = {
      set(true)
    }

  }

  /**
   * Concrete implementation of Open state
   */
  private object Open extends State {

    /**
     * Stores at what time the breaker opens in millis since epoch
     */
    private val openAsOf = new AtomicLong

    /**
     * Fail-fast on any invocation
     *
     * @param body Implementation of the call that needs protected
     * @param executor ExecutionContext used for listener execution
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    override def invoke[T](body: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {
      Promise.failed[T](new CircuitBreakerOpenException(remainingTimeout().timeLeft))
    }

    /**
     * Calculate remaining timeout to inform the caller in case a backoff algorithm is useful
     *
     * @return [[akka.util.Deadline]] to when the breaker will attempt a reset by transitioning to half-open
     */
    private def remainingTimeout(): Deadline = openAsOf.get match {
      case 0L ⇒ Deadline.now
      case t  ⇒ (t.millis + resetTimeout).fromNow
    }

    /**
     * No-op for open, calls never are executed so cannot succeed or fail
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    override def callSucceeds()(implicit executor: ExecutionContext): Unit = {}

    /**
     * No-op for open, calls never are executed so cannot succeed or fail
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    override def callFails()(implicit executor: ExecutionContext): Unit = {}

    /**
     * On entering this state, schedule an attempted reset via [[akka.actor.Scheduler]] and store the entry time to
     * calculate remaining time before attempted reset.
     *
     * @param executor ExecutionContext used for listener execution
     * @return
     */
    override def _enter()(implicit executor: ExecutionContext): Unit = {
      openAsOf.set(System.currentTimeMillis)
      scheduler.scheduleOnce(resetTimeout) {
        attemptReset()
      }
    }

  }

}

/**
 * Exception thrown when Circuit Breaker is open.
 *
 * @param remainingDuration Stores remaining time before attempting a reset.  Zero duration means the breaker is
 *                          currently in half-open state.
 * @param message Defaults to "Circuit Breaker is open; calls are failing fast"
 */
class CircuitBreakerOpenException (
  val remainingDuration: Duration,
  message: String = "Circuit Breaker is open; calls are failing fast")
  extends AkkaException(message) with NoStackTrace

