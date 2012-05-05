package akka.actor.mailbox

import java.lang.Throwable
import java.util.concurrent.Semaphore
import akka.util.duration._
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong, AtomicInteger }
import akka.actor.Scheduler
import akka.util.{ Duration, Deadline }
import akka.AkkaException
import java.util.concurrent.locks.ReentrantLock

/**
 * akka.actor.mailbox
 * Date: 4/28/12
 * Time: 11:07 AM
 */

class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: Duration, resetTimeout: Duration) {

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
    currentState.get().onCall(body, false)
  }

  def withAsyncCircuitBreaker[T](body: ⇒ T): T = {
    currentState.get().onCall(body, true)
  }

  def onAsyncSuccess() {
    if (currentState.get().exceedsAsyncDeadline)
      currentState.get().callFails()
    else
      currentState.get().callSucceeds()
  }

  def onAsyncFailure() {
    currentState.get().callFails()
  }

  private[this] def tripBreaker() {
    currentState.get() match {
      case CircuitBreakerOpen ⇒ throw new IllegalStateException("TripBreaker transition not valid from Open state")
      case _                  ⇒
    }
    currentState.set(CircuitBreakerOpen)
    CircuitBreakerOpen.enter()
  }

  private[this] def resetBreaker() {
    currentState.get() match {
      case CircuitBreakerHalfOpen ⇒
      case _                      ⇒ throw new IllegalStateException("ResetBreaker transition only valid from Half-Open state")
    }
    currentState.set(CircuitBreakerClosed)
    CircuitBreakerClosed.enter()
  }

  private[this] def attemptReset() {
    currentState.get() match {
      case CircuitBreakerOpen ⇒
      case _                  ⇒ throw new IllegalStateException("AttemptReset transition only valid from Open state")
    }
    currentState.set(CircuitBreakerHalfOpen)
    CircuitBreakerHalfOpen.enter()
  }

  trait CircuitBreakerState {

    private val lock = new ReentrantLock()
    private var listeners = List[ScalaObject]()
    private val asyncDeadline = new AtomicReference[Option[Deadline]](None)

    def exceedsAsyncDeadline = {
      asyncDeadline.get() match {
        case None    ⇒ false
        case Some(d) ⇒ d.isOverdue()
      }
    }

    def addListener[T](listener: () ⇒ T) {
      lock.lock()
      try
        listeners = listener :: listeners
      finally
        lock.unlock()
    }

    def notifyTransitionListeners() {
      lock.lock()
      try
        listeners.foreach(l ⇒ {
          scheduler.scheduleOnce(Duration.Zero) {
            l.asInstanceOf[() ⇒ _]()
          }
        })
      finally
        lock.unlock()
    }

    def exceedsDeadline[T](body: ⇒ T): (T, Boolean) = {
      val deadline = callTimeout.fromNow
      val result = body
      (result, deadline.isOverdue())
    }

    def callThrough[T](body: ⇒ T, async: Boolean): T = {
      try {
        if (async)
          asyncDeadline.set(Some(callTimeout.fromNow))
        else
          asyncDeadline.set(None)

        val (result, deadlineExceeded) = exceedsDeadline {
          body
        }

        if (!async)
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

    def onCall[T](body: ⇒ T, async: Boolean): T

    def callSucceeds()

    def callFails()

    def enter()

  }

  object CircuitBreakerOpen extends CircuitBreakerState {

    private val openAsOf = new AtomicLong(0)

    def onCall[T](body: ⇒ T, async: Boolean): T = {
      throw new CircuitBreakerOpenException(remainingTimeout().timeLeft)
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

    def onCall[T](body: ⇒ T, async: Boolean): T = {
      if (!halfOpenSemaphore.tryAcquire())
        throw new CircuitBreakerHalfOpenException(CircuitBreakerOpen.remainingTimeout().timeLeft)
      try {
        callThrough(body, async)
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

    def onCall[T](body: ⇒ T, async: Boolean): T = {
      callThrough(body, async)
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

class CircuitBreakerHalfOpenException(override val remainingDuration: Duration) extends CircuitBreakerOpenException(remainingDuration)

