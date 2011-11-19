/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Map
import scala.annotation.tailrec

import System.{ currentTimeMillis ⇒ newTimestamp }

import akka.actor.ActorSystem

/**
 * Implementation of 'The Phi Accrual Failure Detector' by Hayashibara et al. as defined in their paper:
 * [http://ddg.jaist.ac.jp/pub/HDY+04.pdf]
 * <p/>
 * A low threshold is prone to generate many wrong suspicions but ensures a quick detection in the event
 * of a real crash. Conversely, a high threshold generates fewer mistakes but needs more time to detect
 * actual crashes
 * <p/>
 * Default threshold is 8, but can be configured in the Akka config.
 */
class AccrualFailureDetector(val threshold: Int = 8, val maxSampleSize: Int = 1000) {

  def this(system: ActorSystem) {
    this(
      system.settings.config.getInt("akka.remote.failure-detector.threshold"),
      system.settings.config.getInt("akka.remote.failure-detector.max-sample-size"))
  }

  private final val PhiFactor = 1.0 / math.log(10.0)

  private case class FailureStats(mean: Double = 0.0D, variance: Double = 0.0D, deviation: Double = 0.0D)

  /**
   * Implement using optimistic lockless concurrency, all state is represented
   * by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    version: Long = 0L,
    failureStats: Map[RemoteAddress, FailureStats] = Map.empty[RemoteAddress, FailureStats],
    intervalHistory: Map[RemoteAddress, Vector[Long]] = Map.empty[RemoteAddress, Vector[Long]],
    timestamps: Map[RemoteAddress, Long] = Map.empty[RemoteAddress, Long])

  private val state = new AtomicReference[State](State())

  /**
   * Returns true if the connection is considered to be up and healthy
   * and returns false otherwise.
   */
  def isAvailable(connection: RemoteAddress): Boolean = phi(connection) < threshold

  /**
   * Records a heartbeat for a connection.
   */
  @tailrec
  final def heartbeat(connection: RemoteAddress) {
    val oldState = state.get

    val latestTimestamp = oldState.timestamps.get(connection)
    if (latestTimestamp.isEmpty) {

      // this is heartbeat from a new connection
      // add starter records for this new connection
      val failureStats = oldState.failureStats + (connection -> FailureStats())
      val intervalHistory = oldState.intervalHistory + (connection -> Vector.empty[Long])
      val timestamps = oldState.timestamps + (connection -> newTimestamp)

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = failureStats,
        intervalHistory = intervalHistory,
        timestamps = timestamps)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) heartbeat(connection) // recur

    } else {
      // this is a known connection
      val timestamp = newTimestamp
      val interval = timestamp - latestTimestamp.get

      val timestamps = oldState.timestamps + (connection -> timestamp) // record new timestamp

      var newIntervalsForConnection =
        oldState.intervalHistory.get(connection).getOrElse(Vector.empty[Long]) :+ interval // append the new interval to history

      if (newIntervalsForConnection.size > maxSampleSize) {
        // reached max history, drop first interval
        newIntervalsForConnection = newIntervalsForConnection drop 0
      }

      val failureStats =
        if (newIntervalsForConnection.size > 1) {

          val mean: Double = newIntervalsForConnection.sum / newIntervalsForConnection.size.toDouble

          val oldFailureStats = oldState.failureStats.get(connection).getOrElse(FailureStats())

          val deviationSum =
            newIntervalsForConnection
              .map(_.toDouble)
              .foldLeft(0.0D)((x, y) ⇒ x + (y - mean))

          val variance: Double = deviationSum / newIntervalsForConnection.size.toDouble
          val deviation: Double = math.sqrt(variance)

          val newFailureStats = oldFailureStats copy (mean = mean,
            deviation = deviation,
            variance = variance)

          oldState.failureStats + (connection -> newFailureStats)
        } else {
          oldState.failureStats
        }

      val intervalHistory = oldState.intervalHistory + (connection -> newIntervalsForConnection)

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = failureStats,
        intervalHistory = intervalHistory,
        timestamps = timestamps)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) heartbeat(connection) // recur
    }
  }

  /**
   * Calculates how likely it is that the connection has failed.
   * <p/>
   * If a connection does not have any records in failure detector then it is
   * considered dead. This is true either if the heartbeat have not started
   * yet or the connection have been explicitly removed.
   * <p/>
   * Implementations of 'Cumulative Distribution Function' for Exponential Distribution.
   * For a discussion on the math read [https://issues.apache.org/jira/browse/CASSANDRA-2597].
   */
  def phi(connection: RemoteAddress): Double = {
    val oldState = state.get
    val oldTimestamp = oldState.timestamps.get(connection)
    if (oldTimestamp.isEmpty) 0.0D // treat unmanaged connections, e.g. with zero heartbeats, as healthy connections
    else {
      val timestampDiff = newTimestamp - oldTimestamp.get
      val mean = oldState.failureStats.get(connection).getOrElse(FailureStats()).mean
      PhiFactor * timestampDiff / mean
    }
  }

  /**
   * Removes the heartbeat management for a connection.
   */
  @tailrec
  final def remove(connection: RemoteAddress) {
    val oldState = state.get

    if (oldState.failureStats.contains(connection)) {
      val failureStats = oldState.failureStats - connection
      val intervalHistory = oldState.intervalHistory - connection
      val timestamps = oldState.timestamps - connection

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = failureStats,
        intervalHistory = intervalHistory,
        timestamps = timestamps)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) remove(connection) // recur
    }
  }
}
