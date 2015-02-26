package akka.stream.impl

import scala.util.control.NonFatal
import org.reactivestreams.{ Subscriber, Publisher, Subscription }

/**
 * INTERNAL API
 */
private[stream] object ReactiveStreamsCompliance {

  final val CanNotSubscribeTheSameSubscriberMultipleTimes =
    "can not subscribe the same subscriber multiple times (see reactive-streams specification, rules 1.10 and 2.12)"

  final val SupportsOnlyASingleSubscriber =
    "only supports one subscriber (which is allowed, see reactive-streams specification, rule 1.12)"

  final val NumberOfElementsInRequestMustBePositiveMsg =
    "The number of requested elements must be > 0 (see reactive-streams specification, rule 3.9)"

  final val TotalPendingDemandMustNotExceedLongMaxValue =
    "Total pending demand MUST NOT be > `java.lang.Long.MAX_VALUE` (see reactive-streams specification, rule 3.17)"

  final def totalPendingDemandMustNotExceedLongMaxValueException: Throwable =
    new IllegalStateException(TotalPendingDemandMustNotExceedLongMaxValue)

  final def numberOfElementsInRequestMustBePositiveException: Throwable =
    new IllegalArgumentException(NumberOfElementsInRequestMustBePositiveMsg)

  final def canNotSubscribeTheSameSubscriberMultipleTimesException: Throwable =
    new IllegalStateException(CanNotSubscribeTheSameSubscriberMultipleTimes)

  final def rejectDuplicateSubscriber[T](subscriber: Subscriber[T]): Unit =
    tryOnError(subscriber, canNotSubscribeTheSameSubscriberMultipleTimesException)

  final def rejectAdditionalSubscriber[T](subscriber: Subscriber[T], rejector: Publisher[T]): Unit =
    tryOnError(subscriber, new IllegalStateException(s"$rejector $SupportsOnlyASingleSubscriber"))

  final def rejectDueToOverflow[T](subscriber: Subscriber[T]): Unit =
    tryOnError(subscriber, totalPendingDemandMustNotExceedLongMaxValueException)

  final def rejectDueToNonPositiveDemand[T](subscriber: Subscriber[T]): Unit =
    tryOnError(subscriber, numberOfElementsInRequestMustBePositiveException)

  @SerialVersionUID(1L)
  sealed trait SpecViolation extends Throwable

  @SerialVersionUID(1L)
  final class SignalThrewException(message: String, cause: Throwable) extends IllegalStateException(message, cause) with SpecViolation

  final def tryOnError[T](subscriber: Subscriber[T], error: Throwable): Unit =
    error match {
      case sv: SpecViolation ⇒ throw new IllegalStateException("It is not legal to try to signal onError with a SpecViolation", sv)
      case other ⇒
        try subscriber.onError(other) catch {
          case NonFatal(t) ⇒ throw new SignalThrewException(subscriber + ".onError", t)
        }
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
