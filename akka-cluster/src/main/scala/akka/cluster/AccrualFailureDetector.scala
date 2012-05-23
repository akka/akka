/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ ActorSystem, Address }
import akka.event.Logging

import scala.collection.immutable.Map
import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

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
class AccrualFailureDetector(
  system: ActorSystem,
  address: Address,
  val threshold: Int = 8,
  val maxSampleSize: Int = 1000,
  val timeMachine: () ⇒ Long = System.currentTimeMillis) {

  private final val PhiFactor = 1.0 / math.log(10.0)

  private val log = Logging(system, "FailureDetector")

  /**
   * Holds the failure statistics for a specific node Address.
   */
  private case class FailureStats(mean: Double = 0.0D, variance: Double = 0.0D, deviation: Double = 0.0D)

  /**
   * Implement using optimistic lockless concurrency, all state is represented
   * by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    version: Long = 0L,
    failureStats: Map[Address, FailureStats] = Map.empty[Address, FailureStats],
    intervalHistory: Map[Address, IndexedSeq[Long]] = Map.empty[Address, IndexedSeq[Long]],
    timestamps: Map[Address, Long] = Map.empty[Address, Long],
    explicitRemovals: Set[Address] = Set.empty[Address])

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
    log.debug("Node [{}] - Heartbeat from connection [{}] ", address, connection)

    val oldState = state.get
    val latestTimestamp = oldState.timestamps.get(connection)

    if (latestTimestamp.isEmpty) {
      // this is heartbeat from a new connection
      // add starter records for this new connection
      val newState = oldState copy (
        version = oldState.version + 1,
        failureStats = oldState.failureStats + (connection -> FailureStats()),
        intervalHistory = oldState.intervalHistory + (connection -> IndexedSeq.empty[Long]),
        timestamps = oldState.timestamps + (connection -> timeMachine()),
        explicitRemovals = oldState.explicitRemovals - connection)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) heartbeat(connection) // recur

    } else {
      // this is a known connection
      val timestamp = timeMachine()
      val interval = timestamp - latestTimestamp.get

      val newIntervalsForConnection = (oldState.intervalHistory.get(connection) match {
        case Some(history) if history.size >= maxSampleSize ⇒
          // reached max history, drop first interval
          history drop 1
        case Some(history) ⇒ history
        case _             ⇒ IndexedSeq.empty[Long]
      }) :+ interval

      val newFailureStats =
        if (newIntervalsForConnection.size > 1) {

          val newMean: Double = newIntervalsForConnection.sum / newIntervalsForConnection.size.toDouble

          val oldConnectionFailureStats = oldState.failureStats.get(connection).getOrElse {
            throw new IllegalStateException("Can't calculate new failure statistics due to missing heartbeat history")
          }

          val deviationSum =
            newIntervalsForConnection
              .map(_.toDouble)
              .foldLeft(0.0D)((x, y) ⇒ x + (y - newMean))

          val newVariance: Double = deviationSum / newIntervalsForConnection.size.toDouble
          val newDeviation: Double = math.sqrt(newVariance)

          val newFailureStats = oldConnectionFailureStats copy (mean = newMean, deviation = newDeviation, variance = newVariance)
          oldState.failureStats + (connection -> newFailureStats)

        } else {
          oldState.failureStats
        }

      val newState = oldState copy (version = oldState.version + 1,
        failureStats = newFailureStats,
        intervalHistory = oldState.intervalHistory + (connection -> newIntervalsForConnection),
        timestamps = oldState.timestamps + (connection -> timestamp), // record new timestamp,
        explicitRemovals = oldState.explicitRemovals - connection)

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
  def phi(connection: Address): Double = {
    val oldState = state.get
    val oldTimestamp = oldState.timestamps.get(connection)

    val phi =
      // if connection has been removed explicitly
      if (oldState.explicitRemovals.contains(connection)) Double.MaxValue
      else if (oldTimestamp.isEmpty) 0.0D // treat unmanaged connections, e.g. with zero heartbeats, as healthy connections
      else {
        val timestampDiff = timeMachine() - oldTimestamp.get

        val mean = oldState.failureStats.get(connection) match {
          case Some(FailureStats(mean, _, _)) ⇒ mean
          case _                              ⇒ throw new IllegalStateException("Can't calculate Failure Detector Phi value for a node that have no heartbeat history")
        }

        if (mean == 0.0D) 0.0D
        else PhiFactor * timestampDiff / mean
      }

    // only log if PHI value is starting to get interesting
    if (phi > 0.0D) log.debug("Node [{}] - Phi value [{}] and threshold [{}] for connection [{}] ", address, phi, threshold, connection)
    phi
  }

  /**
   * Removes the heartbeat management for a connection.
   */
  @tailrec
  final def remove(connection: Address) {
    val oldState = state.get

    if (oldState.failureStats.contains(connection)) {
      val newState = oldState copy (version = oldState.version + 1,
        failureStats = oldState.failureStats - connection,
        intervalHistory = oldState.intervalHistory - connection,
        timestamps = oldState.timestamps - connection,
        explicitRemovals = oldState.explicitRemovals + connection)

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) remove(connection) // recur
    }
  }
}
