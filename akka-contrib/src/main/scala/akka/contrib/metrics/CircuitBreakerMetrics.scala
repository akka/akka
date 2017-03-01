package akka.contrib.metrics

import akka.NotUsed
import akka.contrib.metrics.Event.{BreakerClosed, BreakerHalfOpened, BreakerOpened}
import akka.pattern.CircuitBreaker
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

sealed trait Event

case object Event {

  /**
    * Event emitted when call through CircuitBreaker succeeds
    *
    * @param time    in milliseconds when event was received by CircuitBreakerMetrics
    * @param elapsed time of call invocation
    */
  final case class CallSuccess(time: Long, elapsed: Long) extends Event

  /**
    * Event emitted when call through CircuitBreaker failed
    *
    * @param time    in milliseconds when event was received by CircuitBreakerMetrics
    * @param elapsed time of call invocation
    */
  final case class CallFailure(time: Long, elapsed: Long) extends Event

  /**
    * Event emitted when call through CircuitBreaker received timeout
    *
    * @param time    in milliseconds when event was received by CircuitBreakerMetrics
    * @param elapsed time of call invocation
    */
  final case class CallTimeout(time: Long, elapsed: Long) extends Event

  /**
    * Event emitted when call through CircuitBreaker was done in open state
    *
    * @param time in milliseconds when event was received by CircuitBreakerMetrics
    */
  final case class CallBreakerOpen(time: Long) extends Event

  /**
    * Event emitted when CircuitBreaker state change into open
    *
    * @param time in milliseconds when event was received by CircuitBreakerMetrics
    */
  final case class BreakerOpened(time: Long) extends Event

  /**
    * Event emitted when CircuitBreaker state change into closed
    *
    * @param time in milliseconds when event was received by CircuitBreakerMetrics
    */
  final case class BreakerClosed(time: Long) extends Event


  /**
    * Event emitted when CircuitBreaker state change into half-open
    *
    * @param time in milliseconds when event was received by CircuitBreakerMetrics
    */
  final case class BreakerHalfOpened(time: Long) extends Event

}

/**
  * Class represents time bucket of collected metrics
  * @param elapsedTotal is total sum of measured elapsed time in milliseconds
  * @param count is total sum of measured calls
  */
final case class TimeBucket(elapsedTotal: Long, count: Long) {
  lazy val average = elapsedTotal / count
}
/**
  * Class represents count bucket of collected metrics
  * @param count is total sum of measured calls
  */
final case class CountBucket(count: Long)

/**
  * Class represents result of measured metric from [[CircuitBreaker]] within time period
  * @param start time in millisecond when measurement started
  * @param stop time in millisecond when measurement finished
  * @param opens list of [[CircuitBreaker]] [[Event.BreakerOpened]] events
  * @param closes list of [[CircuitBreaker]] [[Event.BreakerClosed]] events
  * @param halfOpens list of [[CircuitBreaker]] [[Event.BreakerHalfOpened]] events
  * @param callSuccesses [[TimeBucket]] for successful calls
  * @param callFailures [[TimeBucket]] for failure calls
  * @param callTimeouts  [[TimeBucket]] for timeout calls
  * @param callBreakerOpens  [[CountBucket]] for calls when [[CircuitBreaker]] was in opened state
  */
final case class TimeBucketResult(
                                   start: Long,
                                   stop: Long,
                                   opens: Seq[BreakerOpened],
                                   closes: Seq[BreakerClosed],
                                   halfOpens: Seq[BreakerHalfOpened],
                                   callSuccesses: TimeBucket,
                                   callFailures: TimeBucket,
                                   callTimeouts: TimeBucket,
                                   callBreakerOpens: CountBucket
                                 )

/**
  * Provides API for collecting metric from provided [[CircuitBreaker]]
  */
object CircuitBreakerMetrics {

  /**
    * Use in order to get access to raw [[CircuitBreaker]] events
    *
    * Due the fact that [[CircuitBreaker]] exposes callback API for receiving events, they cannot be backpressured and
    * must be buffered in configured buffer.
    *
    * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
    * there is no space available in the buffer.
    *
    * The strategy [[akka.stream.OverflowStrategy.backpressure]] is not supported and will result in stream fail.
    *
    * The buffer can be disabled by using `bufferSize` of 0 and then received event will be drop in place if there is
    * no demand from downstream.
    *
    * @param breaker which should be monitored
    * @param bufferSize size of buffer in element count
    * @param overflowStrategy Strategy that is used when incoming events cannot fit inside the buffer
    * @return source of [[Event]]
    */
  def events(breaker: CircuitBreaker, bufferSize: Int = 1024, overflowStrategy: OverflowStrategy = OverflowStrategy.fail): Source[Event, NotUsed] =
    Source.fromGraph(new CircuitBreakerEventsStage(breaker, bufferSize, overflowStrategy))

  /**
    * Use in order to get received [[CircuitBreaker]] metrics averaged within configured interval.
    *
    * Due the fact that [[CircuitBreaker]] exposes callback API for receiving events, they cannot be backpressured and
    * calculated [[TimeBucketResult]] are buffered in configured buffer.
    *
    * If there is no space in buffer the oldest calculated bucket will be dropped.
    *
    * @param breaker which should be monitored
    * @param bufferSize size of buffer in element count
    * @return source of [[Event]]
    */
  def timeBuckets(breaker: CircuitBreaker, interval: FiniteDuration, bufferSize: Int = 32): Source[TimeBucketResult, NotUsed] ={
    require(bufferSize > 0, "bufferSize must be greater than 0")
    Source.fromGraph(new CircuitBreakerTimeBucketsStage(breaker, interval, bufferSize))
  }

}
