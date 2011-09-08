/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

// ==================================================================== //
// Based on code by Christopher Schmidt released under Apache 2 license //
// ==================================================================== //

import scala.collection.immutable.HashMap
import System._

import java.util.concurrent.atomic.{ AtomicLong, AtomicReference, AtomicInteger }

/**
 * Holder companion object for creating and retrieving all configured CircuitBreaker (CircuitBreaker) instances.
 * (Enhancements could be to put some clever ThreadLocal stuff in here).
 * <pre>
 *   try withCircuitBreaker(circuitBreakerName) {
 *     // do something dangerous here
 *   } catch {
 *     case e: CircuitBreakerOpenException     ⇒ // handle...
 *     case e: CircuitBreakerHalfOpenException ⇒ // handle...
 *     case e: Exception                       ⇒ // handle...
 *   }
 * </pre>
 */
object CircuitBreaker {

  private var circuitBreaker = Map.empty[String, CircuitBreaker]

  /**
   * Factory mathod that creates a new CircuitBreaker with a given name and configuration.
   *
   * @param name name or id of the new CircuitBreaker
   * @param config CircuitBreakerConfiguration to configure the new CircuitBreaker
   */
  def addCircuitBreaker(name: String, config: CircuitBreakerConfiguration): Unit = {
    circuitBreaker.get(name) match {
      case None    ⇒ circuitBreaker += ((name, new CircuitBreakerImpl(config)))
      case Some(x) ⇒ throw new IllegalArgumentException("CircuitBreaker [" + name + "] already configured")
    }

  }

  def hasCircuitBreaker(name: String) = circuitBreaker.contains(name)

  /**
   * CircuitBreaker retrieve method.
   *
   * @param name String name or id of the CircuitBreaker
   * @return CircuitBreaker with name or id name
   */
  private[akka] def apply(name: String): CircuitBreaker = {
    circuitBreaker.get(name) match {
      case Some(x) ⇒ x
      case None    ⇒ throw new IllegalArgumentException("CircuitBreaker [" + name + "] not configured")
    }
  }
}

/**
 * Basic Mixin for using CircuitBreaker Scope method.
 */
trait UsingCircuitBreaker {
  def withCircuitBreaker[T](name: String)(f: ⇒ T): T = {
    CircuitBreaker(name).invoke(f)
  }
}

/**
 * @param timeout timout for trying again
 * @param failureThreshold threshold of errors till breaker will open
 */
case class CircuitBreakerConfiguration(timeout: Long, failureThreshold: Int)

/**
 * Interface definition for CircuitBreaker
 */
private[akka] trait CircuitBreaker {

  /**
   * increments and gets the actual failure count
   *
   * @return Int failure count
   */
  var failureCount: Int

  /**
   * @return Long milliseconds at trip
   */
  var tripTime: Long

  /**
   * function that has to be applied in CircuitBreaker scope
   */
  def invoke[T](f: ⇒ T): T

  /**
   * trip CircuitBreaker, store trip time
   */
  def trip()

  /**
   * sets failure count to 0
   */
  def resetFailureCount()

  /**
   * set state to Half Open
   */
  def attemptReset()

  /**
   * reset CircuitBreaker to configured defaults
   */
  def reset()

  /**
   * @return Int configured failure threshold
   */
  def failureThreshold: Int

  /**
   * @return Long configured timeout
   */
  def timeout: Long
}

/**
 * CircuitBreaker base class for all configuration things
 * holds all thread safe (atomic) private members
 */
private[akka] abstract class CircuitBreakerBase(config: CircuitBreakerConfiguration) extends CircuitBreaker {
  private var _state = new AtomicReference[States]
  private var _failureThreshold = new AtomicInteger(config.failureThreshold)
  private var _timeout = new AtomicLong(config.timeout)
  private var _failureCount = new AtomicInteger(0)
  private var _tripTime = new AtomicLong

  protected def state_=(s: States) {
    _state.set(s)
  }

  protected def state = _state.get

  def failureThreshold = _failureThreshold get

  def timeout = _timeout get

  def failureCount_=(i: Int) {
    _failureCount.set(i)
  }

  def failureCount = _failureCount.incrementAndGet

  def tripTime_=(l: Long) {
    _tripTime.set(l)
  }

  def tripTime = _tripTime.get

}

/**
 * CircuitBreaker implementation class for changing states.
 */
private[akka] class CircuitBreakerImpl(config: CircuitBreakerConfiguration) extends CircuitBreakerBase(config) {
  reset()

  def reset() {
    resetFailureCount
    state = new ClosedState(this)
  }

  def resetFailureCount() {
    failureCount = 0
  }

  def attemptReset() {
    state = new HalfOpenState(this)
  }

  def trip() {
    tripTime = currentTimeMillis
    state = new OpenState(this)
  }

  def invoke[T](f: ⇒ T): T = {
    state.preInvoke
    try {
      val ret = f
      state.postInvoke
      ret
    } catch {
      case e: Throwable ⇒ {
        state.onError(e)
        throw e
      }
    }
  }
}