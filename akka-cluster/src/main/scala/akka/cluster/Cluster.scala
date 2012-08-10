/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions

import akka.actor._
import akka.actor.Status._
import akka.ConfigurationException
import akka.dispatch.MonitorableThreadFactory
import akka.event.Logging
import akka.pattern._
import akka.remote._
import akka.routing._
import akka.util._
import scala.concurrent.util.duration._
import scala.concurrent.util.{ Duration, Deadline }
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import akka.util.internal.HashedWheelTimer
import concurrent.{ ExecutionContext, Await }

/**
 * Cluster Extension Id and factory for creating Cluster extension.
 * Example:
 * {{{
 *  if (Cluster(system).isLeader) { ... }
 * }}}
 */
object Cluster extends ExtensionId[Cluster] with ExtensionIdProvider {
  override def get(system: ActorSystem): Cluster = super.get(system)

  override def lookup = Cluster

  override def createExtension(system: ExtendedActorSystem): Cluster = {
    val clusterSettings = new ClusterSettings(system.settings.config, system.name)

    val failureDetector = {
      import clusterSettings.{ FailureDetectorImplementationClass ⇒ fqcn }
      system.dynamicAccess.createInstanceFor[FailureDetector](
        fqcn, Seq(classOf[ActorSystem] -> system, classOf[ClusterSettings] -> clusterSettings)).fold(
          e ⇒ throw new ConfigurationException("Could not create custom failure detector [" + fqcn + "] due to:" + e.toString),
          identity)
    }

    new Cluster(system, failureDetector)
  }
}

/**
 * This module is responsible for Gossiping cluster information. The abstraction maintains the list of live
 * and dead members. Periodically i.e. every 1 second this module chooses a random member and initiates a round
 * of Gossip with it.
 * <p/>
 * During each round of gossip exchange it sends Gossip to random node with
 * newer or older state information, if any, based on the current gossip overview,
 * with some probability. Otherwise Gossip to any random live node.
 *
 * Example:
 * {{{
 *  if (Cluster(system).isLeader) { ... }
 * }}}
 */
class Cluster(system: ExtendedActorSystem, val failureDetector: FailureDetector) extends Extension with ClusterEnvironment {

  /**
   * Represents the state for this Cluster. Implemented using optimistic lockless concurrency.
   * All state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(memberMembershipChangeListeners: Set[MembershipChangeListener] = Set.empty)

  if (!system.provider.isInstanceOf[RemoteActorRefProvider])
    throw new ConfigurationException("ActorSystem[" + system + "] needs to have a 'RemoteActorRefProvider' enabled in the configuration")

  private val remote: RemoteActorRefProvider = system.provider.asInstanceOf[RemoteActorRefProvider]

  val settings = new ClusterSettings(system.settings.config, system.name)
  import settings._

  val selfAddress = remote.transport.address

  private val _isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Cluster")

  log.info("Cluster Node [{}] - is starting up...", selfAddress)

  private val state = new AtomicReference[State](State())

  /**
   * Read only view of cluster state, updated periodically by
   * ClusterCoreDaemon. Access with `latestGossip`.
   */
  @volatile
  private[cluster] var _latestGossip: Gossip = Gossip()

  /**
   * INTERNAL API
   * Read only view of internal cluster stats, updated periodically by
   * ClusterCoreDaemon. Access with `latestStats`.
   */
  @volatile
  private[cluster] var _latestStats = ClusterStats()

  // ========================================================
  // ===================== WORK DAEMONS =====================
  // ========================================================

  /**
   * INTERNAL API
   */
  private[cluster] val scheduler: Scheduler with Closeable = {
    if (system.settings.SchedulerTickDuration > SchedulerTickDuration) {
      log.info("Using a dedicated scheduler for cluster. Default scheduler can be used if configured " +
        "with 'akka.scheduler.tick-duration' [{} ms] <=  'akka.cluster.scheduler.tick-duration' [{} ms].",
        system.settings.SchedulerTickDuration.toMillis, SchedulerTickDuration.toMillis)
      new DefaultScheduler(
        new HashedWheelTimer(log,
          system.threadFactory match {
            case tf: MonitorableThreadFactory ⇒ tf.copy(name = tf.name + "-cluster-scheduler")
            case tf                           ⇒ tf
          },
          SchedulerTickDuration,
          SchedulerTicksPerWheel),
        log)
    } else {
      // delegate to system.scheduler, but don't close over system
      val systemScheduler = system.scheduler
      new Scheduler with Closeable {
        override def close(): Unit = () // we are using system.scheduler, which we are not responsible for closing

        override def schedule(initialDelay: Duration, frequency: Duration,
                              receiver: ActorRef, message: Any)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, frequency, receiver, message)

        override def schedule(initialDelay: Duration, frequency: Duration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, frequency)(f)

        override def schedule(initialDelay: Duration, frequency: Duration,
                              runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, frequency, runnable)

        override def scheduleOnce(delay: Duration,
                                  runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay, runnable)

        override def scheduleOnce(delay: Duration, receiver: ActorRef,
                                  message: Any)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay, receiver, message)

        override def scheduleOnce(delay: Duration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay)(f)
      }
    }
  }

  // create supervisor for daemons under path "/system/cluster"
  private val clusterDaemons: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new ClusterDaemon(this)).
      withDispatcher(UseDispatcher), name = "cluster")
  }

  /**
   * INTERNAL API
   */
  private[cluster] val clusterCore: ActorRef = {
    implicit val timeout = system.settings.CreationTimeout
    Await.result((clusterDaemons ? InternalClusterAction.GetClusterCoreRef).mapTo[ActorRef], timeout.duration)
  }

  system.registerOnTermination(shutdown())

  private val clusterJmx = new ClusterJmx(this, log)
  clusterJmx.createMBean()

  log.info("Cluster Node [{}] - has started up successfully", selfAddress)

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  def self: Member = latestGossip.member(selfAddress)

  /**
   * Returns true if the cluster node is up and running, false if it is shut down.
   */
  def isRunning: Boolean = _isRunning.get

  /**
   * Latest gossip.
   */
  def latestGossip: Gossip = _latestGossip

  /**
   * Member status for this node ([[akka.cluster.MemberStatus]]).
   *
   * NOTE: If the node has been removed from the cluster (and shut down) then it's status is set to the 'REMOVED' tombstone state
   *       and is no longer present in the node ring or any other part of the gossiping state. However in order to maintain the
   *       model and the semantics the user would expect, this method will in this situation return `MemberStatus.Removed`.
   */
  def status: MemberStatus = self.status

  /**
   * Is this node the leader?
   */
  def isLeader: Boolean = latestGossip.isLeader(selfAddress)

  /**
   * Get the address of the current leader.
   */
  def leader: Address = latestGossip.leader match {
    case Some(x) ⇒ x
    case None    ⇒ throw new IllegalStateException("There is no leader in this cluster")
  }

  /**
   * Is this node a singleton cluster?
   */
  def isSingletonCluster: Boolean = latestGossip.isSingletonCluster

  /**
   * Checks if we have a cluster convergence.
   *
   * @return Some(convergedGossip) if convergence have been reached and None if not
   */
  def convergence: Option[Gossip] = latestGossip match {
    case gossip if gossip.convergence ⇒ Some(gossip)
    case _                            ⇒ None
  }

  /**
   * Returns true if the node is UP or JOINING.
   */
  def isAvailable: Boolean = latestGossip.isAvailable(selfAddress)

  /**
   * Make it possible to override/configure seedNodes from tests without
   * specifying in config. Addresses are unknown before startup time.
   */
  def seedNodes: IndexedSeq[Address] = SeedNodes

  /**
   * Registers a listener to subscribe to cluster membership changes.
   */
  @tailrec
  final def registerListener(listener: MembershipChangeListener): Unit = {
    val localState = state.get
    val newListeners = localState.memberMembershipChangeListeners + listener
    val newState = localState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(localState, newState)) registerListener(listener) // recur
  }

  /**
   * Unsubscribes to cluster membership changes.
   */
  @tailrec
  final def unregisterListener(listener: MembershipChangeListener): Unit = {
    val localState = state.get
    val newListeners = localState.memberMembershipChangeListeners - listener
    val newState = localState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(localState, newState)) unregisterListener(listener) // recur
  }

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * A 'Join(thisNodeAddress)' command is sent to the node to join.
   */
  def join(address: Address): Unit =
    clusterCore ! InternalClusterAction.JoinTo(address)

  /**
   * Send command to issue state transition to LEAVING for the node specified by 'address'.
   */
  def leave(address: Address): Unit =
    clusterCore ! ClusterUserAction.Leave(address)

  /**
   * Send command to DOWN the node specified by 'address'.
   */
  def down(address: Address): Unit =
    clusterCore ! ClusterUserAction.Down(address)

  // ========================================================
  // ===================== INTERNAL API =====================
  // ========================================================

  /**
   * INTERNAL API.
   *
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   *
   * Should not called by the user. The user can issue a LEAVE command which will tell the node
   * to go through graceful handoff process `LEAVE -> EXITING -> REMOVED -> SHUTDOWN`.
   */
  private[cluster] def shutdown(): Unit = {
    if (_isRunning.compareAndSet(true, false)) {
      log.info("Cluster Node [{}] - Shutting down cluster Node and cluster daemons...", selfAddress)

      // FIXME isTerminated check can be removed when ticket #2221 is fixed
      // now it prevents logging if system is shutdown (or in progress of shutdown)
      if (!clusterDaemons.isTerminated)
        system.stop(clusterDaemons)

      scheduler.close()

      clusterJmx.unregisterMBean()

      log.info("Cluster Node [{}] - Cluster node successfully shut down", selfAddress)
    }
  }

  /**
   * INTERNAL API
   */
  private[cluster] def notifyMembershipChangeListeners(members: SortedSet[Member]): Unit = {
    // FIXME run callbacks async (to not block the cluster)
    state.get.memberMembershipChangeListeners foreach { _ notify members }
  }

  /**
   * INTERNAL API
   */
  private[cluster] def latestStats: ClusterStats = _latestStats

  /**
   * INTERNAL API
   */
  private[cluster] def publishLatestGossip(gossip: Gossip): Unit = _latestGossip = gossip

  /**
   * INTERNAL API
   */
  private[cluster] def publishLatestStats(stats: ClusterStats): Unit = _latestStats = stats

}

/**
 * Interface for membership change listener.
 */
trait MembershipChangeListener {
  def notify(members: SortedSet[Member]): Unit
}

/**
 * Interface for meta data change listener.
 */
trait MetaDataChangeListener {
  def notify(meta: Map[String, Array[Byte]]): Unit
}
