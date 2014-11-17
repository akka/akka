/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.reactivestreams.{ Subscription, Subscriber }

import scala.util.control.NonFatal

object ReactiveStreamsConstants {

  final val CanNotSubscribeTheSameSubscriberMultipleTimes =
    "can not subscribe the same subscriber multiple times (see reactive-streams specification, rules 1.10 and 2.12)"

  final val SupportsOnlyASingleSubscriber =
    "only supports one subscriber (which is allowed, see reactive-streams specification, rule 1.12)"

  final val NumberOfElementsInRequestMustBePositiveMsg =
    "The number of requested elements must be > 0 (see reactive-streams specification, rule 3.9)"

  final val TotalPendingDemandMustNotExceedLongMaxValue =
    "Total pending demand MUST NOT be > `java.lang.Long.MAX_VALUE` (see reactive-streams specification, rule 3.17)"

  final def validateRequest(n: Long): Unit =
    if (n < 1) throw new IllegalArgumentException(NumberOfElementsInRequestMustBePositiveMsg) with SpecViolation

  sealed trait SpecViolation {
    self: Throwable ⇒
    def violation: Throwable = self // this method is needed because Scalac is not smart enough to handle it otherwise
  }
  //FIXME serialVersionUid?
  final class SignalThrewException(message: String, cause: Throwable) extends IllegalStateException(message, cause) with SpecViolation

  final def tryOnError[T](subscriber: Subscriber[T], error: Throwable): Unit =
    try subscriber.onError(error) catch {
      case NonFatal(t) ⇒ throw new SignalThrewException(subscriber + ".onError", t)
    }

  final def tryOnNext[T](subscriber: Subscriber[T], element: T): Unit =
    try subscriber.onNext(element) catch {
      case NonFatal(t) ⇒ throw new SignalThrewException(subscriber + ".onNext", t)
    }

  final def tryOnSubscribe[T](subscriber: Subscriber[T], subscription: Subscription): Unit =
    try subscriber.onSubscribe(subscription) catch {
      case NonFatal(t) ⇒ throw new SignalThrewException(subscriber + ".onSubscribe", t)
    }

  final def tryOnComplete[T](subscriber: Subscriber[T]): Unit =
    try subscriber.onComplete() catch {
      case NonFatal(t) ⇒ throw new SignalThrewException(subscriber + ".onComplete", t)
    }
}
