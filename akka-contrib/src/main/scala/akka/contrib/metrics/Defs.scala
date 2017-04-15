package akka.contrib.metrics

import akka.contrib.metrics.Event.{ BreakerClosed, BreakerHalfOpened, BreakerOpened }

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
 * Class represents result of measured metric from [[akka.pattern.CircuitBreaker]] within time period
 * @param start time in millisecond when measurement started
 * @param stop time in millisecond when measurement finished
 * @param opens list of [[akka.pattern.CircuitBreaker]] [[Event.BreakerOpened]] events
 * @param closes list of [[akka.pattern.CircuitBreaker]] [[Event.BreakerClosed]] events
 * @param halfOpens list of [[akka.pattern.CircuitBreaker]] [[Event.BreakerHalfOpened]] events
 * @param callSuccesses [[TimeBucket]] for successful calls
 * @param callFailures [[TimeBucket]] for failure calls
 * @param callTimeouts  [[TimeBucket]] for timeout calls
 * @param callBreakerOpens  [[CountBucket]] for calls when [[akka.pattern.CircuitBreaker]] was in opened state
 */
final case class TimeBucketResult(
  start:            Long,
  stop:             Long,
  opens:            Seq[BreakerOpened],
  closes:           Seq[BreakerClosed],
  halfOpens:        Seq[BreakerHalfOpened],
  callSuccesses:    TimeBucket,
  callFailures:     TimeBucket,
  callTimeouts:     TimeBucket,
  callBreakerOpens: CountBucket
)
