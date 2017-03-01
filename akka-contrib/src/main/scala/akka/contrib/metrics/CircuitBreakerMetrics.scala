package akka.contrib.metrics

import akka.NotUsed
import akka.contrib.metrics.Event.{BreakerClosed, BreakerHalfOpened, BreakerOpened}
import akka.pattern.CircuitBreaker
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

sealed trait Event

case object Event {

  final case class CallSuccess(time: Long, elapsed: Long) extends Event

  final case class CallFailure(time: Long, elapsed: Long) extends Event

  final case class CallTimeout(time: Long, elapsed: Long) extends Event

  final case class CallBreakerOpen(time: Long) extends Event

  final case class BreakerOpened(time: Long) extends Event

  final case class BreakerClosed(time: Long) extends Event

  final case class BreakerHalfOpened(time: Long) extends Event

}

final case class TimeBucket(elapsedTotal: Long, count: Long) {
  lazy val average = elapsedTotal / count
}

final case class TimeBucketResult(
                                   start: Long,
                                   stop: Long,
                                   opens: Seq[BreakerOpened],
                                   closes: Seq[BreakerClosed],
                                   halfOpens: Seq[BreakerHalfOpened],
                                   callSuccesses: TimeBucket,
                                   callFailures: TimeBucket,
                                   callTimeouts: TimeBucket,
                                   callBreakerOpens: TimeBucket
                                 )

object CircuitBreakerMetrics {
  def events(breaker: CircuitBreaker, buffer: Int = 1024, overflowStrategy: OverflowStrategy = OverflowStrategy.fail): Source[Event, NotUsed] =
    Source.fromGraph(new CircuitBreakerEventsStage(breaker, buffer, overflowStrategy))

  def timeBuckets(breaker: CircuitBreaker, interval: FiniteDuration, buffer: Long = 32,
                  overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead): Source[TimeBucketResult, NotUsed] = ???
}
