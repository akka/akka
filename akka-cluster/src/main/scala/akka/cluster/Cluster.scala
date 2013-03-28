/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.annotation.tailrec
import scala.collection.immutable
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import akka.util.internal.HashedWheelTimer
import scala.concurrent.{ ExecutionContext, Await }
import com.typesafe.config.ConfigFactory
import akka.remote.DefaultFailureDetectorRegistry
import akka.remote.FailureDetector
import com.typesafe.config.Config
import akka.event.LoggingAdapter
import java.util.concurrent.ThreadFactory
import scala.util.control.NonFatal

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
    case c: ClusterActorRefProvider ⇒ c.transport.defaultAddress
    case other ⇒ throw new ConfigurationException(
      "ActorSystem [%s] needs to have a 'ClusterActorRefProvider' enabled in the configuration, currently uses [%s]".
        format(system, other.getClass.getName))
  }

  /**
   * roles that this member has
   */
  def selfRoles: Set[String] = settings.Roles

  /**
   * Java API: roles that this member has
   */
  def getSelfRoles: java.util.Set[String] =
    scala.collection.JavaConverters.setAsJavaSetConverter(selfRoles).asJava

  private val _isTerminated = new AtomicBoolean(false)
  private val log = Logging(system, "Cluster")
  // ClusterJmx is initialized as the last thing in the constructor
  private var clusterJmx: Option[ClusterJmx] = None

  log.info("Cluster Node [{}] - is starting up...", selfAddress)

  val failureDetector: FailureDetectorRegistry[Address] = {
    def createFailureDetector(): FailureDetector = {
      import settings.{ FailureDetectorImplementationClass ⇒ fqcn }
      system.dynamicAccess.createInstanceFor[FailureDetector](
        fqcn, List(classOf[Config] -> settings.FailureDetectorConfig)).recover({
          case e ⇒ throw new ConfigurationException(
            s"Could not create custom cluster failure detector [$fqcn] due to: ${e.toString}", e)
        }).get
    }

    new DefaultFailureDetectorRegistry(() ⇒ createFailureDetector())
  }

  // ========================================================
  // ===================== WORK DAEMONS =====================
  // ========================================================

  /**
   * INTERNAL API
   */
  private[cluster] val scheduler: Scheduler = {
    if (system.scheduler.maxFrequency < 1.second / SchedulerTickDuration) {
      import scala.collection.JavaConverters._
      log.info("Using a dedicated scheduler for cluster. Default scheduler can be used if configured " +
        "with 'akka.scheduler.tick-duration' [{} ms] <=  'akka.cluster.scheduler.tick-duration' [{} ms].",
        (1000 / system.scheduler.maxFrequency).toInt, SchedulerTickDuration.toMillis)

      val cfg = ConfigFactory.parseString(
        s"akka.scheduler.tick-duration=${SchedulerTickDuration.toMillis}ms").withFallback(
          system.settings.config)
      val threadFactory = system.threadFactory match {
        case tf: MonitorableThreadFactory ⇒ tf.withName(tf.name + "-cluster-scheduler")
        case tf                           ⇒ tf
      }
      system.dynamicAccess.createInstanceFor[Scheduler](system.settings.SchedulerClass, immutable.Seq(
        classOf[Config] -> cfg,
        classOf[LoggingAdapter] -> log,
        classOf[ThreadFactory] -> threadFactory)).get
    } else {
      // delegate to system.scheduler, but don't close over system
      val systemScheduler = system.scheduler
      new Scheduler with Closeable {
        override def close(): Unit = () // we are using system.scheduler, which we are not responsible for closing

        override def maxFrequency: Double = systemScheduler.maxFrequency

        override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration,
                              runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.schedule(initialDelay, interval, runnable)

        override def scheduleOnce(delay: FiniteDuration,
                                  runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
          systemScheduler.scheduleOnce(delay, runnable)
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
    try {
      Await.result((clusterDaemons ? InternalClusterAction.GetClusterCoreRef).mapTo[ActorRef], timeout.duration)
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "Failed to startup Cluster")
        shutdown()
        throw e
    }
  }

  @volatile
  private var readViewStarted = false
  private[cluster] lazy val readView: ClusterReadView = {
    val readView = new ClusterReadView(this)
    readViewStarted = true
    readView
  }

  system.registerOnTermination(shutdown())

  if (JmxEnabled)
    clusterJmx = {
      val jmx = new ClusterJmx(this, log)
      jmx.createMBean()
      Some(jmx)
    }

  log.info("Cluster Node [{}] - has started up successfully", selfAddress)

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  /**
   * Returns true if this cluster instance has be shutdown.
   */
  def isTerminated: Boolean = _isTerminated.get

  /**
   * Subscribe to cluster domain events.
   * The `to` Class can be [[akka.cluster.ClusterEvent.ClusterDomainEvent]]
   * or subclass.
   *
   * A snapshot of [[akka.cluster.ClusterEvent.CurrentClusterState]]
   * will be sent to the subscriber as the first event.
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

  /**
   * The supplied thunk will be run, once, when current cluster member is `Up`.
   * Typically used together with configuration option `akka.cluster.min-nr-of-members'
   * to defer some action, such as starting actors, until the cluster has reached
   * a certain size.
   */
  def registerOnMemberUp[T](code: ⇒ T): Unit =
    registerOnMemberUp(new Runnable { def run = code })

  /**
   * Java API: The supplied callback will be run, once, when current cluster member is `Up`.
   * Typically used together with configuration option `akka.cluster.min-nr-of-members'
   * to defer some action, such as starting actors, until the cluster has reached
   * a certain size.
   */
  def registerOnMemberUp(callback: Runnable): Unit = clusterDaemons ! InternalClusterAction.AddOnMemberUpListener(callback)

  // ========================================================
  // ===================== INTERNAL API =====================
  // ========================================================

  /**
   * Make it possible to join the specified seed nodes without defining them
   * in config. Especially useful from tests when Addresses are unknown
   * before startup time.
   */
  private[cluster] def joinSeedNodes(seedNodes: immutable.IndexedSeq[Address]): Unit =
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
    if (_isTerminated.compareAndSet(false, true)) {
      log.info("Cluster Node [{}] - Shutting down cluster Node and cluster daemons...", selfAddress)

      system.stop(clusterDaemons)
      if (readViewStarted) readView.close()

      closeScheduler()

      clusterJmx foreach { _.unregisterMBean() }

      log.info("Cluster Node [{}] - Cluster node successfully shut down", selfAddress)
    }
  }

  private def closeScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

}
