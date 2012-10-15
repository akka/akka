/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicBoolean }
import akka.AkkaException
import akka.actor.Scheduler
import akka.util.Unsafe
import scala.util.control.NoStackTrace
import java.util.concurrent.{ Callable, CopyOnWriteArrayList }
import scala.concurrent.{ ExecutionContext, Future, Promise, Await }
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.Success

/**
 * Companion object providing factory methods for Circuit Breaker which runs callbacks in caller's thread
 */
object CircuitBreaker {

  /**
   * Synchronous execution context to run in caller's thread - used by companion object factory methods
   */
  private[CircuitBreaker] val syncExecutionContext = new ExecutionContext {
    override def execute(runnable: Runnable): Unit = runnable.run()
    override def reportFailure(t: Throwable): Unit = ()
  }

  /**
   * Callbacks run in caller's thread when using withSyncCircuitBreaker, and in same ExecutionContext as the passed
   * in Future when using withCircuitBreaker. To use another ExecutionContext for the callbacks you can specify the
   * executor in the constructor.
   *
   * @param scheduler Reference to Akka scheduler
   * @param maxFailures Maximum number of failures before opening the circuit
   * @param callTimeout [[scala.concurrent.duration.FiniteDuration]] of time after which to consider a call a failure
   * @param resetTimeout [[scala.concurrent.duration.FiniteDuration]] of time after which to attempt to close the circuit
   */
  def apply(scheduler: Scheduler, maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration): CircuitBreaker =
    new CircuitBreaker(scheduler, maxFailures, callTimeout, resetTimeout)(syncExecutionContext)

  /**
   * Callbacks run in caller's thread when using withSyncCircuitBreaker, and in same ExecutionContext as the passed
   * in Future when using withCircuitBreaker. To use another ExecutionContext for the callbacks you can specify the
   * executor in the constructor.
   * Java API alias for apply
   *
   * @param scheduler Reference to Akka scheduler
   * @param maxFailures Maximum number of failures before opening the circuit
   * @param callTimeout [[scala.concurrent.duration.FiniteDuration]] of time after which to consider a call a failure
   * @param resetTimeout [[scala.concurrent.duration.FiniteDuration]] of time after which to attempt to close the circuit
   */
  def create(scheduler: Scheduler, maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration): CircuitBreaker =
    apply(scheduler, maxFailures, callTimeout, resetTimeout)
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
 * @param scheduler Reference to Akka scheduler
 * @param maxFailures Maximum number of failures before opening the circuit
 * @param callTimeout [[scala.concurrent.duration.FiniteDuration]] of time after which to consider a call a failure
 * @param resetTimeout [[scala.concurrent.duration.FiniteDuration]] of time after which to attempt to close the circuit
 * @param executor [[scala.concurrent.ExecutionContext]] used for execution of state transition listeners
 */
class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)(implicit executor: ExecutionContext) extends AbstractCircuitBreaker {

  def this(executor: ExecutionContext, scheduler: Scheduler, maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration) = {
    this(scheduler, maxFailures, callTimeout, resetTimeout)(executor)
  }

  /**
   * Holds reference to current state of CircuitBreaker - *access only via helper methods*
   */
  @volatile
  private[this] var _currentStateDoNotCallMeDirectly: State = Closed

  /**
   * Helper method for access to underlying state via Unsafe
   *
   * @param oldState Previous state on transition
   * @param newState Next state on transition
   * @return Whether the previous state matched correctly
   */
  @inline
  private[this] def swapState(oldState: State, newState: State): Boolean =
    Unsafe.instance.compareAndSwapObject(this, AbstractCircuitBreaker.stateOffset, oldState, newState)

  /**
   * Helper method for accessing underlying state via Unsafe
   *
   * @return Reference to current state
   */
  @inline
  private[this] def currentState: State =
    Unsafe.instance.getObjectVolatile(this, AbstractCircuitBreaker.stateOffset).asInstanceOf[State]

  /**
   * Wraps invocations of asynchronous calls that need to be protected
   *
   * @param body Call needing protected
   * @tparam T return type from call
   * @return [[scala.concurrent.Future]] containing the call result
   */
  def withCircuitBreaker[T](body: ⇒ Future[T]): Future[T] = currentState.invoke(body)

  /**
   * Java API for withCircuitBreaker
   *
   * @param body Call needing protected
   * @tparam T return type from call
   * @return [[scala.concurrent.Future]] containing the call result
   */
  def callWithCircuitBreaker[T](body: Callable[Future[T]]): Future[T] = withCircuitBreaker(body.call)

  /**
   * Wraps invocations of synchronous calls that need to be protected
   *
   * Calls are run in caller's thread
   *
   * @param body Call needing protected
   * @tparam T return type from call
   * @return The result of the call
   */
  def withSyncCircuitBreaker[T](body: ⇒ T): T =
    Await.result(
      withCircuitBreaker(try Future.successful(body) catch { case NonFatal(t) ⇒ Future.failed(t) }),
      callTimeout)

  /**
   * Java API for withSyncCircuitBreaker
   *
   * @param body Call needing protected
   * @tparam T return type from call
   * @return The result of the call
   */

  def callWithSyncCircuitBreaker[T](body: Callable[T]): T = withSyncCircuitBreaker(body.call)

  /**
   * Adds a callback to execute when circuit breaker opens
   *
   * The callback is run in the [[scala.concurrent.ExecutionContext]] supplied in the constructor.
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
   * Java API for onOpen
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onOpen[T](callback: Callable[T]): CircuitBreaker = onOpen(callback.call)

  /**
   * Adds a callback to execute when circuit breaker transitions to half-open
   *
   * The callback is run in the [[scala.concurrent.ExecutionContext]] supplied in the constructor.
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
   * JavaAPI for onHalfOpen
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onHalfOpen[T](callback: Callable[T]): CircuitBreaker = onHalfOpen(callback.call)

  /**
   * Adds a callback to execute when circuit breaker state closes
   *
   * The callback is run in the [[scala.concurrent.ExecutionContext]] supplied in the constructor.
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
   * JavaAPI for onClose
   *
   * @param callback Handler to be invoked on state change
   * @tparam T Type supplied to assist with type inference, otherwise ignored by implementation
   * @return CircuitBreaker for fluent usage
   */
  def onClose[T](callback: Callable[T]): CircuitBreaker = onClose(callback.call)

  /**
   * Retrieves current failure count.
   *
   * @return count
   */
  private[akka] def currentFailureCount: Int = Closed.get

  /**
   * Implements consistent transition between states
   *
   * @param fromState State being transitioning from
   * @param toState State being transitioning from
   * @throws IllegalStateException if an invalid transition is attempted
   */
  private def transition(fromState: State, toState: State): Unit =
    if (swapState(fromState, toState))
      toState.enter()
    else
      throw new IllegalStateException("Illegal transition attempted from: " + fromState + " to " + toState)

  /**
   * Trips breaker to an open state.  This is valid from Closed or Half-Open states.
   *
   * @param fromState State we're coming from (Closed or Half-Open)
   */
  private def tripBreaker(fromState: State): Unit = transition(fromState, Open)

  /**
   * Resets breaker to a closed state.  This is valid from an Half-Open state only.
   *
   */
  private def resetBreaker(): Unit = transition(HalfOpen, Closed)

  /**
   * Attempts to reset breaker by transitioning to a half-open state.  This is valid from an Open state only.
   *
   */
  private def attemptReset(): Unit = transition(Open, HalfOpen)

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
    def addListener[T](listener: () ⇒ T): Unit = listeners add listener

    /**
     * Test for whether listeners exist
     *
     * @return whether listeners exist
     */
    private def hasListeners: Boolean = !listeners.isEmpty

    /**
     * Notifies the listeners of the transition event via a Future executed in implicit parameter ExecutionContext
     *
     * @return Promise which executes listener in supplied [[scala.concurrent.ExecutionContext]]
     */
    protected def notifyTransitionListeners() {
      if (hasListeners) {
        val iterator = listeners.iterator
        while (iterator.hasNext) {
          val listener = iterator.next
          //FIXME per @viktorklang: it's a bit wasteful to create Futures for one-offs, just use EC.execute instead
          Future(listener())(executor)
        }
      }
    }

    /**
     * Shared implementation of call across all states.  Thrown exception or execution of the call beyond the allowed
     * call timeout is counted as a failed call, otherwise a successful call
     *
     * @param body Implementation of the call
     * @tparam T Return type of the call's implementation
     * @return Future containing the result of the call
     */
    def callThrough[T](body: ⇒ Future[T]): Future[T] = {
      val deadline = callTimeout.fromNow
      val bodyFuture = try body catch { case NonFatal(t) ⇒ Future.failed(t) }
      bodyFuture.onComplete({
        case s: Success[_] if !deadline.isOverdue() ⇒ callSucceeds()
        case _                                      ⇒ callFails()
      })(CircuitBreaker.syncExecutionContext)
      bodyFuture
    }

    /**
     * Abstract entry point for all states
     *
     * @param body Implementation of the call that needs protected
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    def invoke[T](body: ⇒ Future[T]): Future[T]

    /**
     * Invoked when call succeeds
     *
     */
    def callSucceeds(): Unit

    /**
     * Invoked when call fails
     *
     */
    def callFails(): Unit

    /**
     * Invoked on the transitioned-to state during transition.  Notifies listeners after invoking subclass template
     * method _enter
     *
     */
    final def enter(): Unit = {
      _enter()
      notifyTransitionListeners()
    }

    /**
     * Template method for concrete traits
     *
     */
    def _enter(): Unit
  }

  /**
   * Concrete implementation of Closed state
   */
  private object Closed extends AtomicInteger with State {

    /**
     * Implementation of invoke, which simply attempts the call
     *
     * @param body Implementation of the call that needs protected
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    override def invoke[T](body: ⇒ Future[T]): Future[T] = callThrough(body)

    /**
     * On successful call, the failure count is reset to 0
     *
     * @return
     */
    override def callSucceeds(): Unit = set(0)

    /**
     * On failed call, the failure count is incremented.  The count is checked against the configured maxFailures, and
     * the breaker is tripped if we have reached maxFailures.
     *
     * @return
     */
    override def callFails(): Unit = if (incrementAndGet() == maxFailures) tripBreaker(Closed)

    /**
     * On entry of this state, failure count is reset.
     *
     * @return
     */
    override def _enter(): Unit = set(0)

    /**
     * Override for more descriptive toString
     *
     * @return
     */
    override def toString: String = "Closed with failure count = " + get()
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
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    override def invoke[T](body: ⇒ Future[T]): Future[T] =
      if (compareAndSet(true, false)) callThrough(body) else Promise.failed[T](new CircuitBreakerOpenException(0.seconds)).future

    /**
     * Reset breaker on successful call.
     *
     * @return
     */
    override def callSucceeds(): Unit = resetBreaker()

    /**
     * Reopen breaker on failed call.
     *
     * @return
     */
    override def callFails(): Unit = tripBreaker(HalfOpen)

    /**
     * On entry, guard should be reset for that first call to get in
     *
     * @return
     */
    override def _enter(): Unit = set(true)

    /**
     * Override for more descriptive toString
     *
     * @return
     */
    override def toString: String = "Half-Open currently testing call for success = " + get()
  }

  /**
   * Concrete implementation of Open state
   */
  private object Open extends AtomicLong with State {

    /**
     * Fail-fast on any invocation
     *
     * @param body Implementation of the call that needs protected
     * @tparam T Return type of protected call
     * @return Future containing result of protected call
     */
    override def invoke[T](body: ⇒ Future[T]): Future[T] =
      Promise.failed[T](new CircuitBreakerOpenException(remainingTimeout().timeLeft)).future

    /**
     * Calculate remaining timeout to inform the caller in case a backoff algorithm is useful
     *
     * @return [[scala.concurrent.duration.Deadline]] to when the breaker will attempt a reset by transitioning to half-open
     */
    private def remainingTimeout(): Deadline = get match {
      case 0L ⇒ Deadline.now
      case t  ⇒ (t.millis + resetTimeout).fromNow
    }

    /**
     * No-op for open, calls are never executed so cannot succeed or fail
     *
     * @return
     */
    override def callSucceeds(): Unit = ()

    /**
     * No-op for open, calls are never executed so cannot succeed or fail
     *
     * @return
     */
    override def callFails(): Unit = ()

    /**
     * On entering this state, schedule an attempted reset via [[akka.actor.Scheduler]] and store the entry time to
     * calculate remaining time before attempted reset.
     *
     * @return
     */
    override def _enter(): Unit = {
      set(System.currentTimeMillis)
      scheduler.scheduleOnce(resetTimeout) {
        attemptReset()
      }
    }

    /**
     * Override for more descriptive toString
     *
     * @return
     */
    override def toString: String = "Open"
  }

}

/**
 * Exception thrown when Circuit Breaker is open.
 *
 * @param remainingDuration Stores remaining time before attempting a reset.  Zero duration means the breaker is
 *                          currently in half-open state.
 * @param message Defaults to "Circuit Breaker is open; calls are failing fast"
 */
class CircuitBreakerOpenException(
  val remainingDuration: FiniteDuration,
  message: String = "Circuit Breaker is open; calls are failing fast")
  extends AkkaException(message) with NoStackTrace
