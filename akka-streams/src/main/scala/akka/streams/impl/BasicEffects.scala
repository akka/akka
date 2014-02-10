package akka.streams.impl

import scala.language.existentials
import rx.async.spi.{ Subscription, Subscriber }

/** Predefined effects */
object BasicEffects {
  // Subscriber

  case class SubscriberOnNext[O](subscriber: Subscriber[O], o: O) extends SideEffect {
    def run() = subscriber.onNext(o)
  }
  case class SubscriberOnComplete(subscriber: Subscriber[_]) extends SideEffect {
    def run() = subscriber.onComplete()
  }
  case class SubscriberOnError(subscriber: Subscriber[_], cause: Throwable) extends SideEffect {
    def run() = subscriber.onError(cause)
  }

  // Subscription

  case class RequestMoreFromSubscription(subscription: Subscription, n: Int) extends SideEffect {
    def run(): Unit = subscription.requestMore(n)
  }
  case class CancelSubscription(subscription: Subscription) extends SideEffect {
    def run(): Unit = subscription.cancel()
  }

  // SyncSink

  case class HandleNextInSink[B](right: SyncSink[B], element: B) extends SingleStep {
    def runOne(): Effect = right.handleNext(element)
  }
  case class CompleteSink(right: SyncSink[_]) extends SingleStep {
    def runOne(): Effect = right.handleComplete()
  }
  case class HandleErrorInSink(right: SyncSink[_], cause: Throwable) extends SingleStep {
    def runOne(): Effect = right.handleError(cause)
  }

  // SYncSource

  case class RequestMoreFromSource(left: SyncSource, n: Int) extends SingleStep {
    def runOne(): Effect = left.handleRequestMore(n)
  }
  case class CancelSource(left: SyncSource) extends SingleStep {
    def runOne(): Effect = left.handleCancel()
  }
}
