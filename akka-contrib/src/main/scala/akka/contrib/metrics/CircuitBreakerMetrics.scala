package akka.contrib.metrics

import akka.NotUsed
import akka.contrib.metrics.stage.{ CircuitBreakerEventsStage, CircuitBreakerTimeBucketsStage }
import akka.pattern.CircuitBreaker
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

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
  def timeBuckets(breaker: CircuitBreaker, interval: FiniteDuration, bufferSize: Int = 32): Source[TimeBucketResult, NotUsed] = {
    require(bufferSize > 0, "bufferSize must be greater than 0")
    Source.fromGraph(new CircuitBreakerTimeBucketsStage(breaker, interval, bufferSize))
  }

}
