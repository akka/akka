package akka.remote

import akka.remote.FailureDetector.Clock
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/**
 * Implementation of 'The Phi Accrual Failure Detector' by Hayashibara et al. as defined in their paper:
 * [http://ddg.jaist.ac.jp/pub/HDY+04.pdf]
 *
 * The suspicion level of failure is given by a value called φ (phi).
 * The basic idea of the φ failure detector is to express the value of φ on a scale that
 * is dynamically adjusted to reflect current network conditions. A configurable
 * threshold is used to decide if φ is considered to be a failure.
 *
 * The value of φ is calculated as:
 *
 * {{{
 * φ = -log10(1 - F(timeSinceLastHeartbeat)
 * }}}
 * where F is the cumulative distribution function of a normal distribution with mean
 * and standard deviation estimated from historical heartbeat inter-arrival times.
 *
 * @param threshold A low threshold is prone to generate many wrong suspicions but ensures a quick detection in the event
 *   of a real crash. Conversely, a high threshold generates fewer mistakes but needs more time to detect
 *   actual crashes
 *
 * @param maxSampleSize Number of samples to use for calculation of mean and standard deviation of
 *   inter-arrival times.
 *
 * @param minStdDeviation Minimum standard deviation to use for the normal distribution used when calculating phi.
 *   Too low standard deviation might result in too much sensitivity for sudden, but normal, deviations
 *   in heartbeat inter arrival times.
 *
 * @param acceptableHeartbeatPause Duration corresponding to number of potentially lost/delayed
 *   heartbeats that will be accepted before considering it to be an anomaly.
 *   This margin is important to be able to survive sudden, occasional, pauses in heartbeat
 *   arrivals, due to for example garbage collect or network drop.
 *
 * @param firstHeartbeatEstimate Bootstrap the stats with heartbeats that corresponds to
 *   to this duration, with a with rather high standard deviation (since environment is unknown
 *   in the beginning)
 *
 * @param clock The clock, returning current time in milliseconds, but can be faked for testing
 *   purposes. It is only used for measuring intervals (duration).
 */
class PhiAccrualFailureDetector(
  val threshold: Double,
  val maxSampleSize: Int,
  val minStdDeviation: FiniteDuration,
  val acceptableHeartbeatPause: FiniteDuration,
  val firstHeartbeatEstimate: FiniteDuration)(
    implicit clock: Clock) extends FailureDetector {

  // guess statistics for first heartbeat,
  // important so that connections with only one heartbeat becomes unavailable
  private val firstHeartbeat: HeartbeatHistory = {
    // bootstrap with 2 entries with rather high standard deviation
    val mean = firstHeartbeatEstimate.toMillis
    val stdDeviation = mean / 4
    HeartbeatHistory(maxSampleSize) :+ (mean - stdDeviation) :+ (mean + stdDeviation)
  }

  private val acceptableHeartbeatPauseMillis = acceptableHeartbeatPause.toMillis

  /**
   * Implement using optimistic lockless concurrency, all state is represented
   * by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    history: HeartbeatHistory = firstHeartbeat,
    timestamp: Option[Long] = None)

  private val state = new AtomicReference[State](State())

  override def isAvailable: Boolean = phi < threshold

  @tailrec
  final override def heartbeat(): Unit = {

    val timestamp = clock()
    val oldState = state.get

    val newHistory = oldState.timestamp match {
      case None ⇒
        // this is heartbeat from a new resource
        // add starter records for this new resource
        firstHeartbeat
      case Some(latestTimestamp) ⇒
        // this is a known connection
        val interval = timestamp - latestTimestamp
        oldState.history :+ interval
    }

    val newState = oldState.copy(history = newHistory, timestamp = Some(timestamp)) // record new timestamp

    // if we won the race then update else try again
    if (!state.compareAndSet(oldState, newState)) heartbeat() // recur
  }

  /**
   * The suspicion level of the accrual failure detector.
   *
   * If a connection does not have any records in failure detector then it is
   * considered healthy.
   */
  def phi: Double = {
    val oldState = state.get
    val oldTimestamp = oldState.timestamp

    if (oldTimestamp.isEmpty) 0.0 // treat unmanaged connections, e.g. with zero heartbeats, as healthy connections
    else {
      val timeDiff = clock() - oldTimestamp.get

      val history = oldState.history
      val mean = history.mean
      val stdDeviation = ensureValidStdDeviation(history.stdDeviation)

      val φ = phi(timeDiff, mean + acceptableHeartbeatPauseMillis, stdDeviation)

      φ
    }
  }

  private[akka] def phi(timeDiff: Long, mean: Double, stdDeviation: Double): Double = {
    val cdf = cumulativeDistributionFunction(timeDiff, mean, stdDeviation)
    -math.log10(1.0 - cdf)
  }

  private val minStdDeviationMillis = minStdDeviation.toMillis

  private def ensureValidStdDeviation(stdDeviation: Double): Double = math.max(stdDeviation, minStdDeviationMillis)

  /**
   * Cumulative distribution function for N(mean, stdDeviation) normal distribution.
   * This is an approximation defined in β Mathematics Handbook (Logistic approximation).
   * Error is 0.00014 at +- 3.16
   */
  private[akka] def cumulativeDistributionFunction(x: Double, mean: Double, stdDeviation: Double): Double = {
    val y = (x - mean) / stdDeviation
    // Cumulative distribution function for N(0, 1)
    1.0 / (1.0 + math.exp(-y * (1.5976 + 0.070566 * y * y)))
  }
}

private[akka] object HeartbeatHistory {

  /**
   * Create an empty HeartbeatHistory, without any history.
   * Can only be used as starting point for appending intervals.
   * The stats (mean, variance, stdDeviation) are not defined for
   * for empty HeartbeatHistory, i.e. throws AritmeticException.
   */
  def apply(maxSampleSize: Int): HeartbeatHistory = HeartbeatHistory(
    maxSampleSize = maxSampleSize,
    intervals = IndexedSeq.empty,
    intervalSum = 0L,
    squaredIntervalSum = 0L)

}

/**
 * Holds the heartbeat statistics for a specific node Address.
 * It is capped by the number of samples specified in `maxSampleSize`.
 *
 * The stats (mean, variance, stdDeviation) are not defined for
 * for empty HeartbeatHistory, i.e. throws AritmeticException.
 */
private[akka] case class HeartbeatHistory private (
  maxSampleSize: Int,
  intervals: IndexedSeq[Long],
  intervalSum: Long,
  squaredIntervalSum: Long) {

  if (maxSampleSize < 1)
    throw new IllegalArgumentException(s"maxSampleSize must be >= 1, got [$maxSampleSize]")
  if (intervalSum < 0L)
    throw new IllegalArgumentException(s"intervalSum must be >= 0, got [$intervalSum]")
  if (squaredIntervalSum < 0L)
    throw new IllegalArgumentException(s"squaredIntervalSum must be >= 0, got [$squaredIntervalSum]")

  def mean: Double = intervalSum.toDouble / intervals.size

  def variance: Double = (squaredIntervalSum.toDouble / intervals.size) - (mean * mean)

  def stdDeviation: Double = math.sqrt(variance)

  @tailrec
  final def :+(interval: Long): HeartbeatHistory = {
    if (intervals.size < maxSampleSize)
      HeartbeatHistory(
        maxSampleSize,
        intervals = intervals :+ interval,
        intervalSum = intervalSum + interval,
        squaredIntervalSum = squaredIntervalSum + pow2(interval))
    else
      dropOldest :+ interval // recur
  }

  private def dropOldest: HeartbeatHistory = HeartbeatHistory(
    maxSampleSize,
    intervals = intervals drop 1,
    intervalSum = intervalSum - intervals.head,
    squaredIntervalSum = squaredIntervalSum - pow2(intervals.head))

  private def pow2(x: Long) = x * x
}
