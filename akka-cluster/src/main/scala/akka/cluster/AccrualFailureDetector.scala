/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ ActorSystem, Address, ExtendedActorSystem }
import akka.remote.RemoteActorRefProvider
import akka.event.Logging
import scala.collection.immutable.Map
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit.NANOSECONDS
import akka.util.Duration
import akka.util.duration._

object AccrualFailureDetector {
  private def realClock: () ⇒ Long = () ⇒ NANOSECONDS.toMillis(System.nanoTime)
}
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
 *
 * @param system Belongs to the [[akka.actor.ActorSystem]]. Used for logging.
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
 *
 */
class AccrualFailureDetector(
  val system: ActorSystem,
  val threshold: Double,
  val maxSampleSize: Int,
  val minStdDeviation: Duration,
  val acceptableHeartbeatPause: Duration,
  val firstHeartbeatEstimate: Duration,
  val clock: () ⇒ Long = AccrualFailureDetector.realClock) extends FailureDetector {

  import AccrualFailureDetector._

  /**
   * Constructor that picks configuration from the settings.
   */
  def this(
    system: ActorSystem,
    settings: ClusterSettings) =
    this(
      system,
      threshold = settings.FailureDetectorThreshold,
      maxSampleSize = settings.FailureDetectorMaxSampleSize,
      minStdDeviation = settings.FailureDetectorMinStdDeviation,
      acceptableHeartbeatPause = settings.FailureDetectorAcceptableHeartbeatPause,
      firstHeartbeatEstimate = settings.HeartbeatInterval,
      clock = AccrualFailureDetector.realClock)

  private val log = Logging(system, "FailureDetector")

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
    version: Long = 0L,
    history: Map[Address, HeartbeatHistory] = Map.empty,
    timestamps: Map[Address, Long] = Map.empty[Address, Long])

  private val state = new AtomicReference[State](State())

  /**
   * Returns true if the connection is considered to be up and healthy
   * and returns false otherwise.
   */
  def isAvailable(connection: Address): Boolean = phi(connection) < threshold

  /**
   * Records a heartbeat for a connection.
   */
  @tailrec
  final def heartbeat(connection: Address) {
    log.debug("Heartbeat from connection [{}] ", connection)

    val timestamp = clock()
    val oldState = state.get

    val newHistory = oldState.timestamps.get(connection) match {
      case None ⇒
        // this is heartbeat from a new connection
        // add starter records for this new connection
        firstHeartbeat
      case Some(latestTimestamp) ⇒
        // this is a known connection
        val interval = timestamp - latestTimestamp
        oldState.history(connection) :+ interval
    }

    val newState = oldState copy (version = oldState.version + 1,
      history = oldState.history + (connection -> newHistory),
      timestamps = oldState.timestamps + (connection -> timestamp)) // record new timestamp

    // if we won the race then update else try again
    if (!state.compareAndSet(oldState, newState)) heartbeat(connection) // recur
  }

  /**
   * The suspicion level of the accrual failure detector.
   *
   * If a connection does not have any records in failure detector then it is
   * considered healthy.
   */
  def phi(connection: Address): Double = {
    val oldState = state.get
    val oldTimestamp = oldState.timestamps.get(connection)

    if (oldTimestamp.isEmpty) 0.0 // treat unmanaged connections, e.g. with zero heartbeats, as healthy connections
    else {
      val timeDiff = clock() - oldTimestamp.get

      val history = oldState.history(connection)
      val mean = history.mean
      val stdDeviation = ensureValidStdDeviation(history.stdDeviation)

      val φ = phi(timeDiff, mean + acceptableHeartbeatPauseMillis, stdDeviation)

      // FIXME change to debug log level, when failure detector is stable
      if (φ > 1.0 && timeDiff < (acceptableHeartbeatPauseMillis + 5000))
        log.info("Phi value [{}] for connection [{}], after [{} ms], based on  [{}]",
          φ, connection, timeDiff, "N(" + mean + ", " + stdDeviation + ")")

      φ
    }
  }

  private[cluster] def phi(timeDiff: Long, mean: Double, stdDeviation: Double): Double = {
    val cdf = cumulativeDistributionFunction(timeDiff, mean, stdDeviation)
    -math.log10(1.0 - cdf)
  }

  private val minStdDeviationMillis = minStdDeviation.toMillis

  private def ensureValidStdDeviation(stdDeviation: Double): Double = math.max(stdDeviation, minStdDeviationMillis)

  /**
   * Cumulative distribution function for N(mean, stdDeviation) normal distribution.
   * This is an approximation defined in β Mathematics Handbook.
   */
  private[cluster] def cumulativeDistributionFunction(x: Double, mean: Double, stdDeviation: Double): Double = {
    val y = (x - mean) / stdDeviation
    // Cumulative distribution function for N(0, 1)
    1.0 / (1.0 + math.exp(-y * (1.5976 + 0.070566 * y * y)))
  }

  /**
   * Removes the heartbeat management for a connection.
   */
  @tailrec
  final def remove(connection: Address): Unit = {
    log.debug("Remove connection [{}] ", connection)
    val oldState = state.get

    if (oldState.history.contains(connection)) {
      val newState = oldState copy (version = oldState.version + 1,
        history = oldState.history - connection,
        timestamps = oldState.timestamps - connection)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) remove(connection) // recur
    }
  }

  def reset(): Unit = {
    @tailrec
    def doReset(): Unit = {
      val oldState = state.get
      val newState = oldState.copy(version = oldState.version + 1, history = Map.empty, timestamps = Map.empty)
      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) doReset() // recur
    }
    log.debug("Resetting failure detector")
    doReset()
  }
}

private[cluster] object HeartbeatHistory {

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
private[cluster] case class HeartbeatHistory private (
  maxSampleSize: Int,
  intervals: IndexedSeq[Long],
  intervalSum: Long,
  squaredIntervalSum: Long) {

  if (maxSampleSize < 1)
    throw new IllegalArgumentException("maxSampleSize must be >= 1, got [%s]" format maxSampleSize)
  if (intervalSum < 0L)
    throw new IllegalArgumentException("intervalSum must be >= 0, got [%s]" format intervalSum)
  if (squaredIntervalSum < 0L)
    throw new IllegalArgumentException("squaredIntervalSum must be >= 0, got [%s]" format squaredIntervalSum)

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