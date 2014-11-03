/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.dispatch.ExecutionContexts
import akka.stream.CancelTermination
import akka.stream.NoopTermination
import akka.stream.StreamSubscriptionTimeoutSettings
import akka.stream.WarnTermination
import org.reactivestreams.Processor
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NoStackTrace

/**
 * Handed to a [[Publisher]] participating in subscription-timeouts.
 *
 * It *MUST* cancel this timeout the earliest it can in it's `subscribe(Subscriber[T])` method to prevent the timeout from being triggered spuriously.
 */
trait SubscriptionTimeout {
  /**
   * Cancels the subscription timeout and returns `true` if the given `Subscriber` is valid to be processed.
   * For example, if termination is in progress already the Processor should not process this incoming subscriber.
   * In case of returning `false` as in "do not handle this subscriber", this method takes care of cancelling the Subscriber
   * automatically by signalling `onError` with an adequate description of the subscription-timeout being exceeded.
   *
   * [[Publisher]] implementations *MUST* use this method to guard any handling of Subscribers (in `Publisher#subscribe`).
   */
  def cancelAndHandle(s: Subscriber[_]): Boolean
}

/**
 * Provides support methods to create Publishers and Subscribers which time-out gracefully,
 * and are cancelled subscribing an `CancellingSubscriber` to the publisher, or by calling `onError` on the timed-out subscriber.
 *
 * See `akka.stream.materializer.subscription-timeout` for configuration options.
 */
trait StreamSubscriptionTimeoutSupport {
  this: Actor with ActorLogging ⇒

  /** Default settings for subscription timeouts. */
  def subscriptionTimeoutSettings: StreamSubscriptionTimeoutSettings

  /**
   * Creates a [[Publisher]] using the given `mkPublisher` function and registers it for subscription-timeout termination,
   * using the default timeout from the configuration.
   *
   * The created Publisher MUST wrap it's code handling a Subscribers incoming subscription in an `if (subscriptionTimeout.cancel())` block.
   * This is in order to avoid races between the timer cancelling the publisher and it acknowlaging an incoming Subscriber.
   */
  def publisherWithStreamSubscriptionTimeout[Pub <: Publisher[_]](mkPublisher: SubscriptionTimeout ⇒ Pub): Pub =
    publisherWithStreamSubscriptionTimeout(subscriptionTimeoutSettings.timeout)(mkPublisher)

  /**
   * Creates a [[Publisher]] using the given `mkPublisher` function and registers it for subscription-timeout termination,
   * using the passed in timeout.
   *
   * The created Publisher MUST wrap it's code handling a Subscribers incoming subscription in an `if (subscriptionTimeout.cancel())` block.
   * This is in order to avoid races between the timer cancelling the publisher and it acknowlaging an incoming Subscriber.
   */
  def publisherWithStreamSubscriptionTimeout[Pub <: Publisher[_]](timeoutOverride: FiniteDuration)(mkPublisher: SubscriptionTimeout ⇒ Pub): Pub = {
    val p = Promise[Publisher[_]]() // to break chicken-and-egg with subscriptionTimeout

    val subscriptionTimeout = scheduleSubscriptionTimeout(p.future, timeoutOverride)
    val pub = mkPublisher(subscriptionTimeout)
    p.success(pub)

    pub
  }

  private def scheduleSubscriptionTimeout(rs: Future[_], timeout: FiniteDuration): SubscriptionTimeout = {
    implicit val dispatcher =
      if (subscriptionTimeoutSettings.dispatcher.trim.isEmpty) context.dispatcher
      else context.system.dispatchers.lookup(subscriptionTimeoutSettings.dispatcher)

    new SubscriptionTimeout {
      private val safeToCancelTimer = new AtomicBoolean(true)

      val subscriptionTimeout = context.system.scheduler.scheduleOnce(timeout, new Runnable {
        override def run(): Unit = {
          if (safeToCancelTimer.compareAndSet(true, false))
            onReactiveStream { terminate(_, timeout) }
        }
      })

      override def cancelAndHandle(s: Subscriber[_]): Boolean = s match {
        case _ if subscriptionTimeout.isCancelled ⇒
          // there was some initial subscription already, which cancelled the timeout => continue normal operation
          true

        case _ if safeToCancelTimer.get ⇒
          // first subscription signal, cancel the subscription-timeout
          safeToCancelTimer.compareAndSet(true, false) && subscriptionTimeout.cancel()
          true

        case CancellingSubscriber if !safeToCancelTimer.get ⇒
          // publisher termination in progress - normally we'd onError all subscribers, except the CancellationSubscriber (!)
          // guaranteed that no other subscribers are coming in now
          true

        case _ ⇒
          // terminated - kill incoming subscribers
          onReactiveStream { rs ⇒
            s.onError(new SubscriptionTimeoutException(s"Publisher (${rs}) you are trying to subscribe to has been shut-down " +
              s"because exceeding it's subscription-timeout.") with NoStackTrace)
          }

          false
      }

      private final def onReactiveStream(block: Any ⇒ Unit) =
        rs.foreach { rs ⇒ block(rs) }(ExecutionContexts.sameThreadExecutionContext)
    }
  }

  private def cancel(rs: Any, timeout: FiniteDuration): Unit = rs match {
    case p: Processor[_, _] ⇒
      log.debug("Cancelling {} Processor's publisher and subscriber sides (after {})", p, timeout)
      p.subscribe(CancellingSubscriber)
      p.onError(new SubscriptionTimeoutException(s"Publisher was not attached to upstream within deadline (${timeout})") with NoStackTrace)

    case p: Publisher[_] ⇒
      log.debug("Cancelling {} using CancellingSubscriber (after: {})", p, timeout)
      p.subscribe(CancellingSubscriber)
  }

  private def warn(rs: Any, timeout: FiniteDuration): Unit = {
    log.warning("Timed out {} detected (after {})! You should investigate if you either cancel or consume all {} instances",
      rs, timeout, rs.getClass.getCanonicalName)
  }
  private def terminate(el: Any, timeout: FiniteDuration): Unit = subscriptionTimeoutSettings.mode match {
    case NoopTermination   ⇒ // ignore...
    case WarnTermination   ⇒ warn(el, timeout)
    case CancelTermination ⇒ cancel(el, timeout)
  }

  private final case object CancellingSubscriber extends Subscriber[Any] {
    override def onSubscribe(s: Subscription): Unit = s.cancel()
    override def onError(t: Throwable): Unit = ()
    override def onComplete(): Unit = ()
    override def onNext(t: Any): Unit = ()
  }

}

class SubscriptionTimeoutException(msg: String) extends RuntimeException(msg)