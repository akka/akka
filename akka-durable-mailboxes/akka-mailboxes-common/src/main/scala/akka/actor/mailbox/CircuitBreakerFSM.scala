package akka.actor.mailbox

import java.lang.{ Throwable, Exception }
import java.util.concurrent.{ Semaphore, TimeUnit }
import akka.actor.ActorSystem
import akka.util.duration._
import akka.util.Duration
import akka.dispatch.{ ExecutionContext, Await, Future }
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong, AtomicInteger }

/**
 * akka.actor.mailbox
 * Date: 4/28/12
 * Time: 11:07 AM
 */

case class CircuitBreakerOpenException(message: String = "Circuit breaker is currently open", remainingTimeout: Long) extends Exception

class CircuitBreakerFSM(system: ActorSystem, maxFailures: Int, callTimeout: Int, resetTimeout: Int, timeUnit: TimeUnit) {

  implicit val ec = ExecutionContext.defaultExecutionContext(system)

  private val resetTimeoutDuration = Duration(resetTimeout, timeUnit)
  private val failureCount = new AtomicInteger(0)
  private val openAsOf = new AtomicLong(0)
  private val halfOpenSemaphore = new Semaphore(1)
  private val callTimeoutDuration = Duration(callTimeout, timeUnit)
  private val currentState: AtomicReference[CircuitBreakerState] = new AtomicReference(CircuitBreakerClosed)

  def isOpen() = currentState.get().isOpen()
  def isHalfOpen() = currentState.get().isHalfOpen()
  def isClosed() = currentState.get().isClosed()

  def currentFailureCount() = failureCount.get()

  def withCircuitBreaker[T](body: ⇒ T): T =
    {
      try {
        currentState.get().onCall(body)
      } catch {
        case e: Throwable ⇒ throw e;
      }
    }

  private def remainingTimeout() = {
    openAsOf.get() match {
      case 0 ⇒ 0L
      case _ ⇒ System.currentTimeMillis() - openAsOf.get()
    }
  }

  private def tripBreaker() {
    currentState.set(CircuitBreakerOpen)
    openAsOf.set(System.currentTimeMillis())
    system.scheduler.scheduleOnce(resetTimeoutDuration) {
      attemptReset()
    }
  }

  private def resetBreaker() {
    currentState.set(CircuitBreakerClosed)
    openAsOf.set(0)
  }

  private def attemptReset() {
    currentState.set(CircuitBreakerHalfOpen)
  }

  trait CircuitBreakerState {
    def onCall[T](body: ⇒ T): T

    def callSucceeds()

    def callFails()

    def isOpen(): Boolean = false

    def isHalfOpen(): Boolean = false

    def isClosed(): Boolean = false
  }

  object CircuitBreakerOpen extends CircuitBreakerState {
    def onCall[T](body: ⇒ T): T = {
      throw new CircuitBreakerOpenException(remainingTimeout = remainingTimeout())
    }

    def callSucceeds() {}

    def callFails() {}

    override def isOpen() = true
  }

  object CircuitBreakerHalfOpen extends CircuitBreakerState {

    def onCall[T](body: ⇒ T): T = {
      if (!halfOpenSemaphore.tryAcquire())
        throw new CircuitBreakerOpenException(remainingTimeout = remainingTimeout())
      try {
        val future = Future {
          body
        }
        val result = Await.result(future, callTimeoutDuration)
        callSucceeds()
        result
      } catch {
        case e: Throwable ⇒ {
          callFails()
          throw e
        }
      } finally {
        halfOpenSemaphore.release()
      }
    }

    def callSucceeds() {
      resetBreaker()
    }

    def callFails() {
      tripBreaker()
    }

    override def isHalfOpen() = true
  }

  object CircuitBreakerClosed extends CircuitBreakerState {

    def onCall[T](body: ⇒ T): T = {
      try {
        val future = Future {
          body
        }
        val result = Await.result(future, callTimeoutDuration)
        callSucceeds()
        result
      } catch {
        case e: Throwable ⇒ {
          callFails()
          throw e
        }
      }
    }

    def callSucceeds() {
      failureCount.set(0)
    }

    def callFails() {
      val count = failureCount.incrementAndGet()
      if (count >= maxFailures)
        thresholdReached()
    }

    def thresholdReached() {
      tripBreaker()
    }

    override def isClosed() = true
  }

}
