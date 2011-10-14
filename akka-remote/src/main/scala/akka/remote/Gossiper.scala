/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.util.duration._
import akka.actor.Scheduler

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

import scala.collection.immutable.Map
import scala.annotation.tailrec

/**
 * This module is responsible for Gossiping cluster information. The abstraction
 * maintains the list of live and dead nodes. Periodically i.e. every 1 second this module
 * chooses a random node and initiates a round of Gossip with it. This module as and when it hears a gossip
 * updates the Failure Detector with the liveness information.
 * <p/>
 * The implementation is based on this paper by Amazon [http://www.cs.cornell.edu/home/rvr/papers/flowgossip.pdf].
 * <p/>
 * Gossip timer task runs every second.
 * <p/>
 * During each of these runs the node initiates gossip exchange according to following rules (as defined in the
 * Cassandra documentation:
 * <p/>
 *   1) Gossip to random live node (if any)
 *   2) Gossip to random unreachable node with certain probability depending on number of unreachable and live nodes
 *   3) If the node gossiped to at (1) was not seed, or the number of live nodes is less than number of seeds,
 *       gossip to random seed with certain probability depending on number of unreachable, seed and live nodes.
 * <p/>
 */
class Gossiper(failureDetector: AccrualFailureDetector, address: InetSocketAddress) {

  // FIXME make configurable
  val initalDelayForGossip = 5 seconds
  val gossipFrequency = 1 seconds
  val timeUnit = TimeUnit.SECONDS

  val seeds = Vector(address) // FIXME read in list of seeds from config

  // Implement using optimistic lockless concurrency, all state is represented
  // by this immutable case class and managed by an AtomicReference
  private case class State(
    nodeStates: Map[InetSocketAddress, Map[]] = Map { (address -> Map.empty[]) },
    aliveNodes: Vector[InetSocketAddress] = Vector.empty[InetSocketAddress],
    deadNodes: Vector[InetSocketAddress] = Vector.empty[InetSocketAddress],
    nodeStateChangeListeners: Vector[NodeStateChangeListeners] = Vector.empty[NodeStateChangeListeners],
    applicationStateChangePublishers: Vector[ApplicationStateChangePublishers] = Vector.empty[ApplicationStateChangePublishers],
    versions: Map[String, Long] = Map.empty[String, Long],
    generation: Long = System.currentTimeMillis)

  private val state = new AtomicReference[State](State())

  Scheduler.schedule(() => initateGossipExchange(), initalDelayForGossip,  gossipFrequency, timeUnit)
  Scheduler.schedule(() => scrutinizeCluster(), initalDelayForGossip + 1,  gossipFrequency, timeUnit)


  @tailrec
  final private def initateGossipExchange() {
    val oldState = state.get

    val versions = oldState.versions map (_ + 1)
    val nodeStates =
      for {
        publisher <- oldState.applicationStateChangePublishers
        version <- versions.get(publisher.name)
        nodeState <- oldState.nodeStates.get(address)
        publisherState <- nodeState.get(publisher.name)
      } yield {
        // FIXME
        // self._node_states[options.address][publisher.name()] =  {key:value, "generation" : publisher.generation(), "version" : version }
        oldState.nodeStates
      }

    val newState = oldState copy (versions = versions, nodeStates = nodeStates)

    // if we won the race then update else try again
    if (!state.compareAndSet(oldState, newState)) initateGossipExchange() // recur
    else {
      // gossip to alive nodes
      val oldAliveNodes = oldState.aliveNodes
      val oldAliveNodesSize = oldAliveNodes.size
      val gossipedToSeed =
        if (oldAliveNodesSize > 0) sendGossip(oldAliveNodes)
        else false

      // gossip to dead nodes
      val oldDeadNodes = oldState.deadNodes
      val oldDeadNodesSize = oldDeadNodes.size
      if (oldDeadNodesSize > 0) {
        val probability: Double = oldDeadNodesSize / (oldAliveNodesSize + 1)
        if (random() < probability) sendGossip(oldDeadNodes)
      }

      if (!gossipedToSeed || oldAliveNodesSize < 1) {
        // gossip to a seed for facilitating partition healing
        if (seeds.head != address) {
          if (oldAliveNodesSize == 0) sendGossip(seeds)
          else {
            val probability = 1.0 / oldAliveNodesSize + oldDeadNodesSize
            if (random() <= probability) sendGossip(seeds)
          }
        }
      }
    }
  }

  /**
   * Gossips to alive nodes. Returns 'true' if it gossiped to a "seed" node.
   */
  private def sendGossip(nodes: Vector[InetSocketAddress]): Boolean {
    true
  }

  @tailrec
  final private def scrutinizeCluster() {
    val oldState = state.get

    val newState = oldState

    // if we won the race then update else try again
    if (!state.compareAndSet(oldState, newState)) scrutinizeCluster() // recur
  }
}

object Gossiper {
  trait ApplicationStateChangePublishers {
    def name: String
    def value: AnyRef
    def generation()
  }

  trait NodeStateChangeListeners {
    def onJoin(node: InetSocketAddress)
    def onAlive(node: InetSocketAddress)
    def onDead(node: InetSocketAddress)
    def onChange(node: InetSocketAddress, name: String, oldValue: AnyRef, newValue: AnyRef)
  }
}
