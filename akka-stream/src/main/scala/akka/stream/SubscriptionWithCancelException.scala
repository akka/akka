/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import org.reactivestreams.Subscription

import scala.util.control.NoStackTrace

/**
 * Extension of Subscription that allows to pass a cause when a subscription is cancelled.
 *
 * Subscribers can check for this trait and use its `cancel(cause)` method instead of the regular
 * cancel method to pass a cancellation cause.
 */
trait SubscriptionWithCancelException extends Subscription {
  final override def cancel() = cancel(SubscriptionWithCancelException.NoMoreElementsNeeded)
  def cancel(cause: Throwable): Unit
}
object SubscriptionWithCancelException {
  case object NoMoreElementsNeeded extends RuntimeException with NoStackTrace
  case object StageWasCompleted extends RuntimeException with NoStackTrace
}
