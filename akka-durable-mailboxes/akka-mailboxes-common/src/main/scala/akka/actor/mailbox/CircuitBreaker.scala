package akka.actor.mailbox

import java.lang.Throwable
import java.util.concurrent.Semaphore
import akka.util.duration._
import akka.dispatch.ExecutionContext
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong, AtomicInteger }
import akka.actor.Scheduler
import collection.mutable.Stack
import akka.util.{ Duration, Deadline }
import akka.AkkaException

/**
 * akka.actor.mailbox
 * Date: 4/28/12
 * Time: 11:07 AM
 */

class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: Duration, resetTimeout: Duration)(implicit ec: ExecutionContext) {

  private val currentState: AtomicReference[CircuitBreakerState] = new AtomicReference(CircuitBreakerClosed)

  def onOpen[T](func: () ⇒ T): CircuitBreaker = {
    CircuitBreakerOpen.addListener(func)
    this
  }

  def onHalfOpen[T](func: () ⇒ T) = {
    CircuitBreakerHalfOpen.addListener(func)
    this
  }

  def onClose[T](func: () ⇒ T) = {
    CircuitBreakerClosed.addListener(func)
    this
  }

  def currentFailureCount() = CircuitBreakerClosed.failureCount.get()

  def withCircuitBreaker[T](body: ⇒ T): T = {
    currentState.get().onCall(body)
  }

  private def tripBreaker() {
    currentState.get() match {
      case CircuitBreakerOpen ⇒ throw new IllegalStateException("TripBreaker transition not valid from Open state")
      case _                  ⇒
    }
    currentState.set(CircuitBreakerOpen)
    CircuitBreakerOpen.enter()
  }

  private def resetBreaker() {
    currentState.get() match {
      case CircuitBreakerHalfOpen ⇒
      case _                      ⇒ throw new IllegalStateException("ResetBreaker transition only valid from Half-Open state")
    }
    currentState.set(CircuitBreakerClosed)
    CircuitBreakerClosed.enter()
  }

  private def attemptReset() {
    currentState.get() match {
      case CircuitBreakerOpen ⇒
      case _                  ⇒ throw new IllegalStateException("AttemptReset transition only valid from Open state")
    }
    currentState.set(CircuitBreakerHalfOpen)
    CircuitBreakerHalfOpen.enter()
  }

  trait CircuitBreakerState {

    private var listeners = List[ScalaObject]()

    def addListener[T](listener: () ⇒ T) {
      listeners = listener :: listeners
    }

    def notifyTransitionListeners() {
      listeners.foreach(l ⇒ {
        scheduler.scheduleOnce(Duration.Zero) {
          l.asInstanceOf[() ⇒ _]()
        }
      })
    }

    def exceedsDeadline[T](body: ⇒ T): (T, Boolean) = {
      val deadline = callTimeout.fromNow
      val result = body
      (result, deadline.isOverdue())
    }

    def onCall[T](body: ⇒ T): T

    def callSucceeds()

    def callFails()

    def enter()

  }

  object CircuitBreakerOpen extends CircuitBreakerState {

    val openAsOf = new AtomicLong(0)

    def onCall[T](body: ⇒ T): T = {
      throw CircuitBreakerOpenException(remainingTimeout().timeLeft)
    }

    def remainingTimeout(): Deadline = openAsOf.get() match {
      case 0 ⇒ Deadline.now
      case _ ⇒ ((openAsOf.get() millis) + resetTimeout).fromNow
    }

    def callSucceeds() {}

    def callFails() {}

    def enter() {
      notifyTransitionListeners()
      openAsOf.set(System.currentTimeMillis())
      scheduler.scheduleOnce(resetTimeout) {
        attemptReset()
      }
    }

  }

  object CircuitBreakerHalfOpen extends CircuitBreakerState {

    private val halfOpenSemaphore = new Semaphore(1)

    def onCall[T](body: ⇒ T): T = {
      if (!halfOpenSemaphore.tryAcquire())
        throw new CircuitBreakerHalfOpenException(CircuitBreakerOpen.remainingTimeout().timeLeft)
      try {
        val (result, deadlineExceeded) = exceedsDeadline {
          body
        }

        if (deadlineExceeded)
          callFails()
        else
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

    def enter() {
      notifyTransitionListeners()
    }
  }

  object CircuitBreakerClosed extends CircuitBreakerState {

    val failureCount = new AtomicInteger(0)

    def onCall[T](body: ⇒ T): T = {
      try {
        val (result, deadlineExceeded) = exceedsDeadline {
          body
        }

        if (deadlineExceeded)
          callFails()
        else
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

    def enter() {
      failureCount.set(0)
      notifyTransitionListeners()
    }
  }

}

case class CircuitBreakerOpenException(remainingDuration: Duration) extends AkkaException

object CircuitBreakerOpenException

class CircuitBreakerHalfOpenException(override val remainingDuration: Duration) extends CircuitBreakerOpenException(remainingDuration)

object CircuitBreakerHalfOpenException

