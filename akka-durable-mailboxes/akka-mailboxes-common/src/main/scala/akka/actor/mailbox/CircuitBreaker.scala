package akka.actor.mailbox

import java.lang.Throwable
import java.util.concurrent.Semaphore
import akka.util.duration._
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong, AtomicInteger }
import akka.actor.Scheduler
import akka.util.{ Duration, Deadline }
import akka.AkkaException
import java.util.UUID
import collection.mutable.HashMap
import scala.Boolean
import java.util.concurrent.locks.{ Lock, ReentrantLock }

/**
 * akka.actor.mailbox
 * Date: 4/28/12
 * Time: 11:07 AM
 */

trait AsyncCircuitBreakerHandle {
  def withCircuitBreaker[T](body: ⇒ T): T
  def onAsyncSuccess()
  def onAsyncFailure()
}

class CircuitBreaker(scheduler: Scheduler, maxFailures: Int, callTimeout: Duration, resetTimeout: Duration) {

  case class AsyncDeadlineToken(id: UUID = UUID.randomUUID())

  private val currentState: AtomicReference[CircuitBreakerState] = new AtomicReference(CircuitBreakerClosed)
  private val currentStateLock: Lock = new ReentrantLock()

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
    currentState.get().onCall(body, None)
  }

  def createAsyncHandle(): AsyncCircuitBreakerHandle = {
    class InternalAsyncHandle(token: AsyncDeadlineToken) extends AsyncCircuitBreakerHandle {
      def withCircuitBreaker[T](body: ⇒ T): T = {
        _withAsyncCircuitBreaker(body, token)
      }
      def onAsyncSuccess() {
        _onAsyncSuccess(token)
      }
      def onAsyncFailure() {
        _onAsyncFailure(token)
      }
    }
    val token = AsyncDeadlineToken()
    new InternalAsyncHandle(token)
  }

  private[this] def withCurrentState(body: (CircuitBreakerState) ⇒ Unit) {
    try {
      currentStateLock.lock()
      val state = currentState.get()
      body(state)
    } finally {
      currentStateLock.unlock()
    }
  }

  private[this] def _withAsyncCircuitBreaker[T](body: ⇒ T, token: AsyncDeadlineToken): T = {
    currentState.get().onCall(body, Some(token))
  }

  private[this] def _onAsyncSuccess(token: AsyncDeadlineToken) {
    withCurrentState { state ⇒
      if (state.exceedsAsyncDeadline(token))
        state.callFails()
      else
        state.callSucceeds()
      state.cleanUpToken(token)
    }
  }

  private[this] def _onAsyncFailure(token: AsyncDeadlineToken) {
    withCurrentState { state ⇒
      state.callFails()
      state.cleanUpToken(token)
    }
  }

  private[this] def tripBreaker() {
    withCurrentState { state ⇒
      state match {
        case CircuitBreakerOpen ⇒ throw new IllegalStateException("TripBreaker transition not valid from Open state")
        case _                  ⇒
      }
      currentState.set(CircuitBreakerOpen)
      CircuitBreakerOpen.enter()
    }
  }

  private[this] def resetBreaker() {
    withCurrentState { state ⇒
      state match {
        case CircuitBreakerHalfOpen ⇒
        case _                      ⇒ throw new IllegalStateException("ResetBreaker transition only valid from Half-Open state")
      }
      currentState.set(CircuitBreakerClosed)
      CircuitBreakerClosed.enter()
    }
  }

  private[this] def attemptReset() {
    withCurrentState { state ⇒
      state match {
        case CircuitBreakerOpen ⇒
        case _                  ⇒ throw new IllegalStateException("AttemptReset transition only valid from Open state")
      }
      currentState.set(CircuitBreakerHalfOpen)
      CircuitBreakerHalfOpen.enter()
    }
  }

  trait CircuitBreakerState {

    private val listenerLock = new ReentrantLock()
    private var listeners = List[ScalaObject]()

    private val deadlineMapLock = new ReentrantLock()
    private val asyncDeadline = new HashMap[AsyncDeadlineToken, Deadline]()

    def exceedsAsyncDeadline(token: AsyncDeadlineToken) = {
      deadlineMapLock.lock()
      try
        asyncDeadline.get(token) match {
          case None    ⇒ false
          case Some(d) ⇒ d.isOverdue()
        }
      finally
        deadlineMapLock.unlock()
    }

    def exceedsDeadline[T](body: ⇒ T): (T, Boolean) = {
      val deadline = callTimeout.fromNow
      val result = body
      (result, deadline.isOverdue())
    }

    def cleanUpToken(token: AsyncDeadlineToken) {
      deadlineMapLock.lock()
      try
        asyncDeadline.remove(token)
      finally
        deadlineMapLock.unlock()
    }

    def addListener[T](listener: () ⇒ T) {
      listenerLock.lock()
      try
        listeners = listener :: listeners
      finally
        listenerLock.unlock()
    }

    def notifyTransitionListeners() {
      listenerLock.lock()
      try
        listeners.foreach(l ⇒ {
          scheduler.scheduleOnce(Duration.Zero) {
            l.asInstanceOf[() ⇒ _]()
          }
        })
      finally
        listenerLock.unlock()
    }

    def callThrough[T](body: ⇒ T, token: Option[AsyncDeadlineToken]): T = {
      try {
        if (token.isDefined) {
          deadlineMapLock.lock()
          try
            asyncDeadline.put(token.get, callTimeout.fromNow)
          finally
            deadlineMapLock.unlock()
        }

        val (result, deadlineExceeded) = exceedsDeadline {
          body
        }

        if (token.isEmpty)
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

    def onCall[T](body: ⇒ T, token: Option[AsyncDeadlineToken]): T

    def callSucceeds()

    def callFails()

    def enter()

  }

  object CircuitBreakerOpen extends CircuitBreakerState {

    private val openAsOf = new AtomicLong(0)

    def onCall[T](body: ⇒ T, token: Option[AsyncDeadlineToken]): T = {
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

    def onCall[T](body: ⇒ T, token: Option[AsyncDeadlineToken]): T = {
      if (!halfOpenSemaphore.tryAcquire())
        throw new CircuitBreakerHalfOpenException(CircuitBreakerOpen.remainingTimeout().timeLeft)
      try {
        callThrough(body, token)
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

    def onCall[T](body: ⇒ T, token: Option[AsyncDeadlineToken]): T = {
      callThrough(body, token)
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

