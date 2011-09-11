/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import System._

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

// NOTE: This CircuitBreaker is work-in-progress and does NOT work as normal ones.
//       It is not meant to be used as a regular CircuitBreaker, since it does not
//       prevent using the failed connection but only calls the registered call-backs.
//       Is meant to be changed a bit and used in the FailurDetector later.

/**
 * <pre>
 *   // Create the CircuitBreaker
 *   val circuitBreaker =
 *     CircuitBreaker(CircuitBreaker.Config(60.seconds, 5))
 *
 *   // Configure the CircuitBreaker actions
 *   circuitBreaker
 *     onOpen {
 *       ...
 *     } onClose {
 *       ...
 *     } onHalfOpen {
 *       ...
 *     }
 *
 *   circuitBreaker {
 *     ...
 *   }
 * </pre>
 */
object CircuitBreaker {
  case class Config(timeout: Duration, failureThreshold: Int)

  private[akka] def apply(implicit config: Config): CircuitBreaker = new CircuitBreaker(config)

}

class CircuitBreaker private (val config: CircuitBreaker.Config) {
  import CircuitBreaker._

  private object InternalState {
    def apply(circuitBreaker: CircuitBreaker): InternalState =
      InternalState(
        0L,
        Closed(circuitBreaker),
        circuitBreaker.config.timeout.toMillis,
        circuitBreaker.config.failureThreshold,
        0L,
        0)
  }

  /**
   * Represents the internal state of the CircuitBreaker.
   */
  private case class InternalState(
    version: Long,
    state: CircuitBreakerState,
    timeout: Long,
    failureThreshold: Int,
    tripTime: Long,
    failureCount: Int,
    onOpenListeners: List[() ⇒ Unit] = Nil,
    onCloseListeners: List[() ⇒ Unit] = Nil,
    onHalfOpenListeners: List[() ⇒ Unit] = Nil)

  private[akka] trait CircuitBreakerState {
    val circuitBreaker: CircuitBreaker

    def onError(e: Throwable)

    def preInvoke()

    def postInvoke()
  }

  /**
   * CircuitBreaker is CLOSED, normal operation.
   */
  private[akka] case class Closed(circuitBreaker: CircuitBreaker) extends CircuitBreakerState {

    def onError(e: Throwable) = {
      circuitBreaker.incrementFailureCount()
      val currentCount = circuitBreaker.failureCount
      val threshold = circuitBreaker.failureThreshold
      if (currentCount >= threshold) circuitBreaker.trip()
    }

    def preInvoke() {}

    def postInvoke() { circuitBreaker.resetFailureCount() }
  }

  /**
   * CircuitBreaker is OPEN. Calls are failing fast.
   */
  private[akka] case class Open(circuitBreaker: CircuitBreaker) extends CircuitBreakerState {

    def onError(e: Throwable) {}

    def preInvoke() {
      val now = currentTimeMillis
      val elapsed = now - circuitBreaker.tripTime
      if (elapsed <= circuitBreaker.timeout)
        circuitBreaker.notifyOpen()
      circuitBreaker.attemptReset()
    }

    def postInvoke() {}
  }

  /**
   * CircuitBreaker is HALF OPEN. Calls are still failing after timeout.
   */
  private[akka] case class HalfOpen(circuitBreaker: CircuitBreaker) extends CircuitBreakerState {

    def onError(e: Throwable) {
      circuitBreaker.trip()
      circuitBreaker.notifyHalfOpen()
    }

    def preInvoke() {}

    def postInvoke() { circuitBreaker.reset() }
  }

  private val ref = new AtomicReference(InternalState(this))

  def timeout = ref.get.timeout

  def failureThreshold = ref.get.failureThreshold

  def failureCount = ref.get.failureCount

  def tripTime = ref.get.tripTime

  @tailrec
  final def incrementFailureCount() {
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      failureCount = oldState.failureCount + 1)
    if (!ref.compareAndSet(oldState, newState)) incrementFailureCount()
  }

  @tailrec
  final def reset() {
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      failureCount = 0,
      state = Closed(this))
    if (!ref.compareAndSet(oldState, newState)) reset()
  }

  @tailrec
  final def resetFailureCount() {
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      failureCount = 0)
    if (!ref.compareAndSet(oldState, newState)) resetFailureCount()
  }

  @tailrec
  final def attemptReset() {
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      state = HalfOpen(this))
    if (!ref.compareAndSet(oldState, newState)) attemptReset()
  }

  @tailrec
  final def trip() {
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      state = Open(this),
      tripTime = currentTimeMillis)
    if (!ref.compareAndSet(oldState, newState)) trip()
  }

  def apply[T](body: ⇒ T): T = {
    val oldState = ref.get
    oldState.state.preInvoke()
    try {
      val ret = body
      oldState.state.postInvoke()
      ret
    } catch {
      case e: Throwable ⇒
        oldState.state.onError(e)
        throw e
    }
  }

  @tailrec
  final def onClose(body: ⇒ Unit): CircuitBreaker = {
    val f = () ⇒ body
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      onCloseListeners = f :: oldState.onCloseListeners)
    if (!ref.compareAndSet(oldState, newState)) onClose(f)
    else this
  }

  @tailrec
  final def onOpen(body: ⇒ Unit): CircuitBreaker = {
    val f = () ⇒ body
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      onOpenListeners = f :: oldState.onOpenListeners)
    if (!ref.compareAndSet(oldState, newState)) onOpen(() ⇒ f)
    else this
  }

  @tailrec
  final def onHalfOpen(body: ⇒ Unit): CircuitBreaker = {
    val f = () ⇒ body
    val oldState = ref.get
    val newState = oldState copy (version = oldState.version + 1,
      onHalfOpenListeners = f :: oldState.onHalfOpenListeners)
    if (!ref.compareAndSet(oldState, newState)) onHalfOpen(() ⇒ f)
    else this
  }

  def notifyOpen() {
    ref.get.onOpenListeners foreach (f ⇒ f())
  }

  def notifyHalfOpen() {
    ref.get.onHalfOpenListeners foreach (f ⇒ f())
  }

  def notifyClosed() {
    ref.get.onCloseListeners foreach (f ⇒ f())
  }
}
