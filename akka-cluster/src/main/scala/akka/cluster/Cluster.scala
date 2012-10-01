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
import scala.concurrent.util.FiniteDuration

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

  override def createExtension(system: ExtendedActorSystem): Cluster = new Cluster(system)
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
class Cluster(val system: ExtendedActorSystem) extends Extension {

  import ClusterEvent._

  val settings = new ClusterSettings(system.settings.config, system.name)
  import settings._

  val selfAddress: Address = system.provider match {
    case c: ClusterActorRefProvider ⇒ c.transport.address
    case other ⇒ throw new ConfigurationException(
      "ActorSystem [%s] needs to have a 'ClusterActorRefProvider' enabled in the configuration, currently uses [%s]".
        format(system, other.getClass.getName))
  }

  private val _isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Cluster")

  log.info("Cluster Node [{}] - is starting up...", selfAddress)

  val failureDetector: FailureDetector = {
    import settings.{ FailureDetectorImplementationClass ⇒ fqcn }
    system.dynamicAccess.createInstanceFor[FailureDetector](
      fqcn, Seq(classOf[ActorSystem] -> system, classOf[ClusterSettings] -> settings)).recover({
        case e ⇒ throw new ConfigurationException("Could not create custom failure detector [" + fqcn + "] due to:" + e.toString)
      }).get
  }

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

        override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration,
                              receiver: ActorRef, message: Any)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, interval, receiver, message)

        override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, interval)(f)

        override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration,
                              runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, interval, runnable)

        override def scheduleOnce(delay: FiniteDuration,
                                  runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay, runnable)

        override def scheduleOnce(delay: FiniteDuration, receiver: ActorRef,
                                  message: Any)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay, receiver, message)

        override def scheduleOnce(delay: FiniteDuration)(f: ⇒ Unit)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay)(f)
      }
    }
  }

  // create supervisor for daemons under path "/system/cluster"
  private val clusterDaemons: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new ClusterDaemon(settings)).
      withDispatcher(UseDispatcher), name = "cluster")
  }

  /**
   * INTERNAL API
   */
  private[cluster] val clusterCore: ActorRef = {
    implicit val timeout = system.settings.CreationTimeout
    Await.result((clusterDaemons ? InternalClusterAction.GetClusterCoreRef).mapTo[ActorRef], timeout.duration)
  }

  @volatile
  private var readViewStarted = false
  private[cluster] lazy val readView: ClusterReadView = {
    val readView = new ClusterReadView(this)
    readViewStarted = true
    readView
  }

  system.registerOnTermination(shutdown())

  private val clusterJmx: Option[ClusterJmx] = {
    val jmx = new ClusterJmx(this, log)
    jmx.createMBean()
    Some(jmx)
  }

  log.info("Cluster Node [{}] - has started up successfully", selfAddress)

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  /**
   * Returns true if the cluster node is up and running, false if it is shut down.
   */
  def isRunning: Boolean = _isRunning.get

  /**
   * Subscribe to cluster domain events.
   * The `to` Class can be [[akka.cluster.ClusterEvent.ClusterDomainEvent]]
   * or subclass. A snapshot of [[akka.cluster.ClusterEvent.CurrentClusterState]]
   * will also be sent to the subscriber.
   */
  def subscribe(subscriber: ActorRef, to: Class[_]): Unit =
    clusterCore ! InternalClusterAction.Subscribe(subscriber, to)

  /**
   * Unsubscribe to all cluster domain events.
   */
  def unsubscribe(subscriber: ActorRef): Unit =
    clusterCore ! InternalClusterAction.Unsubscribe(subscriber, None)

  /**
   * Unsubscribe to a specific type of cluster domain events,
   * matching previous `subscribe` registration.
   */
  def unsubscribe(subscriber: ActorRef, to: Class[_]): Unit =
    clusterCore ! InternalClusterAction.Unsubscribe(subscriber, Some(to))

  /**
   * Publish current (full) state of the cluster to subscribers,
   * that are subscribing to [[akka.cluster.ClusterEvent.ClusterDomainEvent]]
   * or [[akka.cluster.ClusterEvent.CurrentClusterState]].
   * If you want this to happen periodically you need to schedule a call to
   * this method yourself.
   */
  def publishCurrentClusterState(): Unit =
    clusterCore ! InternalClusterAction.PublishCurrentClusterState(None)

  /**
   * Publish current (full) state of the cluster to the specified
   * receiver. If you want this to happen periodically you need to schedule
   * a call to this method yourself.
   */
  def sendCurrentClusterState(receiver: ActorRef): Unit =
    clusterCore ! InternalClusterAction.PublishCurrentClusterState(Some(receiver))

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
   * Make it possible to join the specified seed nodes without defining them
   * in config. Especially useful from tests when Addresses are unknown
   * before startup time.
   */
  private[cluster] def joinSeedNodes(seedNodes: IndexedSeq[Address]): Unit =
    clusterCore ! InternalClusterAction.JoinSeedNodes(seedNodes)

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

      system.stop(clusterDaemons)
      if (readViewStarted) readView.close()

      scheduler.close()

      clusterJmx foreach { _.unregisterMBean() }

      log.info("Cluster Node [{}] - Cluster node successfully shut down", selfAddress)
    }
  }

}

