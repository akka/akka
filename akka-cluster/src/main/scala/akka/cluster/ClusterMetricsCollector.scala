/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

// TODO remove metrics

import java.io.Closeable
import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }
import java.lang.management.{ OperatingSystemMXBean, MemoryMXBean, ManagementFactory }
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import scala.collection.immutable
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom
import scala.util.{ Try, Success, Failure }
import akka.ConfigurationException
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.cluster.MemberStatus.Up
import akka.cluster.MemberStatus.WeaklyUp
import akka.event.Logging
import java.lang.management.MemoryUsage

/**
 * INTERNAL API.
 *
 * Cluster metrics is primarily for load-balancing of nodes. It controls metrics sampling
 * at a regular frequency, prepares highly variable data for further analysis by other entities,
 * and publishes the latest cluster metrics data around the node ring and local eventStream
 * to assist in determining the need to redirect traffic to the least-loaded nodes.
 *
 * Metrics sampling is delegated to the [[akka.cluster.MetricsCollector]].
 *
 * Smoothing of the data for each monitored process is delegated to the
 * [[akka.cluster.EWMA]] for exponential weighted moving average.
 */
private[cluster] class ClusterMetricsCollector(publisher: ActorRef) extends Actor with ActorLogging {

  import InternalClusterAction._
  import ClusterEvent._
  import Member.addressOrdering
  import context.dispatcher
  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, scheduler, settings }
  import cluster.settings._
  import cluster.InfoLogger._

  /**
   * The node ring gossipped that contains only members that are Up.
   */
  var nodes: immutable.SortedSet[Address] = immutable.SortedSet.empty

  /**
   * The latest metric values with their statistical data.
   */
  var latestGossip: MetricsGossip = MetricsGossip.empty

  /**
   * The metrics collector that samples data on the node.
   */
  val collector: MetricsCollector = MetricsCollector(context.system.asInstanceOf[ExtendedActorSystem], settings)

  /**
   * Start periodic gossip to random nodes in cluster
   */
  val gossipTask = scheduler.schedule(PeriodicTasksInitialDelay max MetricsGossipInterval,
    MetricsGossipInterval, self, GossipTick)

  /**
   * Start periodic metrics collection
   */
  val metricsTask = scheduler.schedule(PeriodicTasksInitialDelay max MetricsInterval,
    MetricsInterval, self, MetricsTick)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
    logInfo("Metrics collection has started successfully")
  }

  def receive = {
    case GossipTick                 ⇒ gossip()
    case MetricsTick                ⇒ collect()
    case msg: MetricsGossipEnvelope ⇒ receiveGossip(msg)
    case state: CurrentClusterState ⇒ receiveState(state)
    case MemberUp(m)                ⇒ addMember(m)
    case MemberWeaklyUp(m)          ⇒ addMember(m)
    case MemberRemoved(m, _)        ⇒ removeMember(m)
    case MemberExited(m)            ⇒ removeMember(m)
    case UnreachableMember(m)       ⇒ removeMember(m)
    case ReachableMember(m) ⇒
      if (m.status == MemberStatus.Up || m.status == MemberStatus.WeaklyUp)
        addMember(m)
    case _: MemberEvent ⇒ // not interested in other types of MemberEvent

  }

  override def postStop: Unit = {
    cluster unsubscribe self
    gossipTask.cancel()
    metricsTask.cancel()
    collector.close()
  }

  /**
   * Adds a member to the node ring.
   */
  def addMember(member: Member): Unit = nodes += member.address

  /**
   * Removes a member from the member node ring.
   */
  def removeMember(member: Member): Unit = {
    nodes -= member.address
    latestGossip = latestGossip remove member.address
    publish()
  }

  /**
   * Updates the initial node ring for those nodes that are [[akka.cluster.MemberStatus]] `Up`.
   */
  def receiveState(state: CurrentClusterState): Unit =
    nodes = state.members collect { case m if m.status == Up || m.status == WeaklyUp ⇒ m.address }

  /**
   * Samples the latest metrics for the node, updates metrics statistics in
   * [[akka.cluster.MetricsGossip]], and publishes the change to the event bus.
   *
   * @see [[akka.cluster.ClusterMetricsCollector#collect]]
   */
  def collect(): Unit = {
    latestGossip :+= collector.sample()
    publish()
  }

  /**
   * Receives changes from peer nodes, merges remote with local gossip nodes, then publishes
   * changes to the event stream for load balancing router consumption, and gossip back.
   */
  def receiveGossip(envelope: MetricsGossipEnvelope): Unit = {
    // remote node might not have same view of member nodes, this side should only care
    // about nodes that are known here, otherwise removed nodes can come back
    val otherGossip = envelope.gossip.filter(nodes)
    latestGossip = latestGossip merge otherGossip
    // changes will be published in the period collect task
    if (!envelope.reply)
      replyGossipTo(envelope.from)
  }

  /**
   * Gossip to peer nodes.
   */
  def gossip(): Unit = selectRandomNode((nodes - selfAddress).toVector) foreach gossipTo

  def gossipTo(address: Address): Unit =
    sendGossip(address, MetricsGossipEnvelope(selfAddress, latestGossip, reply = false))

  def replyGossipTo(address: Address): Unit =
    sendGossip(address, MetricsGossipEnvelope(selfAddress, latestGossip, reply = true))

  def sendGossip(address: Address, envelope: MetricsGossipEnvelope): Unit =
    context.actorSelection(self.path.toStringWithAddress(address)) ! envelope

  def selectRandomNode(addresses: immutable.IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  /**
   * Publishes to the event stream.
   */
  def publish(): Unit = publisher ! PublishEvent(ClusterMetricsChanged(latestGossip.nodes))

}

/**
 * INTERNAL API
 */
private[cluster] object MetricsGossip {
  val empty = MetricsGossip(Set.empty[NodeMetrics])
}

/**
 * INTERNAL API
 *
 * @param nodes metrics per node
 */
@SerialVersionUID(1L)
private[cluster] final case class MetricsGossip(nodes: Set[NodeMetrics]) {

  /**
   * Removes nodes if their correlating node ring members are not [[akka.cluster.MemberStatus]] `Up`.
   */
  def remove(node: Address): MetricsGossip = copy(nodes = nodes filterNot (_.address == node))

  /**
   * Only the nodes that are in the `includeNodes` Set.
   */
  def filter(includeNodes: Set[Address]): MetricsGossip =
    copy(nodes = nodes filter { includeNodes contains _.address })

  /**
   * Adds new remote [[akka.cluster.NodeMetrics]] and merges existing from a remote gossip.
   */
  def merge(otherGossip: MetricsGossip): MetricsGossip =
    otherGossip.nodes.foldLeft(this) { (gossip, nodeMetrics) ⇒ gossip :+ nodeMetrics }

  /**
   * Adds new local [[akka.cluster.NodeMetrics]], or merges an existing.
   */
  def :+(newNodeMetrics: NodeMetrics): MetricsGossip = nodeMetricsFor(newNodeMetrics.address) match {
    case Some(existingNodeMetrics) ⇒
      copy(nodes = nodes - existingNodeMetrics + (existingNodeMetrics merge newNodeMetrics))
    case None ⇒ copy(nodes = nodes + newNodeMetrics)
  }

  /**
   * Returns [[akka.cluster.NodeMetrics]] for a node if exists.
   */
  def nodeMetricsFor(address: Address): Option[NodeMetrics] = nodes find { n ⇒ n.address == address }

}

/**
 * INTERNAL API
 * Envelope adding a sender address to the gossip.
 */
@SerialVersionUID(1L)
private[cluster] final case class MetricsGossipEnvelope(from: Address, gossip: MetricsGossip, reply: Boolean)
  extends ClusterMessage

private[cluster] object EWMA {
  /**
   * math.log(2)
   */
  private val LogOf2 = 0.69315

  /**
   * Calculate the alpha (decay factor) used in [[akka.cluster.EWMA]]
   * from specified half-life and interval between observations.
   * Half-life is the interval over which the weights decrease by a factor of two.
   * The relevance of each data sample is halved for every passing half-life duration,
   * i.e. after 4 times the half-life, a data sample’s relevance is reduced to 6% of
   * its original relevance. The initial relevance of a data sample is given by
   * 1 – 0.5 ^ (collect-interval / half-life).
   */
  def alpha(halfLife: FiniteDuration, collectInterval: FiniteDuration): Double = {
    val halfLifeMillis = halfLife.toMillis
    require(halfLife.toMillis > 0, "halfLife must be > 0 s")
    val decayRate = LogOf2 / halfLifeMillis
    1 - math.exp(-decayRate * collectInterval.toMillis)
  }
}

/**
 * The exponentially weighted moving average (EWMA) approach captures short-term
 * movements in volatility for a conditional volatility forecasting model. By virtue
 * of its alpha, or decay factor, this provides a statistical streaming data model
 * that is exponentially biased towards newer entries.
 *
 * http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average
 *
 * An EWMA only needs the most recent forecast value to be kept, as opposed to a standard
 * moving average model.
 *
 * INTERNAL API
 *
 * @param alpha decay factor, sets how quickly the exponential weighting decays for past data compared to new data,
 *   see http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average
 *
 * @param value the current exponentially weighted moving average, e.g. Y(n - 1), or,
 *             the sampled value resulting from the previous smoothing iteration.
 *             This value is always used as the previous EWMA to calculate the new EWMA.
 *
 */
@SerialVersionUID(1L)
private[cluster] final case class EWMA(value: Double, alpha: Double) {

  require(0.0 <= alpha && alpha <= 1.0, "alpha must be between 0.0 and 1.0")

  /**
   * Calculates the exponentially weighted moving average for a given monitored data set.
   *
   * @param xn the new data point
   * @return a new [[akka.cluster.EWMA]] with the updated value
   */
  def :+(xn: Double): EWMA = {
    val newValue = (alpha * xn) + (1 - alpha) * value
    if (newValue == value) this // no change
    else copy(value = newValue)
  }

}

/**
 * Metrics key/value.
 *
 * Equality of Metric is based on its name.
 *
 * @param name the metric name
 * @param value the metric value, which must be a valid numerical value,
 *   a valid value is neither negative nor NaN/Infinite.
 * @param average the data stream of the metric value, for trending over time. Metrics that are already
 *   averages (e.g. system load average) or finite (e.g. as number of processors), are not trended.
 */
@SerialVersionUID(1L)
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
final case class Metric private[cluster] (name: String, value: Number, private[cluster] val average: Option[EWMA])
  extends MetricNumericConverter {

  require(defined(value), s"Invalid Metric [$name] value [$value]")

  /**
   * Updates the data point, and if defined, updates the data stream (average).
   * Returns the updated metric.
   */
  def :+(latest: Metric): Metric =
    if (this sameAs latest) average match {
      case Some(avg)                        ⇒ copy(value = latest.value, average = Some(avg :+ latest.value.doubleValue))
      case None if latest.average.isDefined ⇒ copy(value = latest.value, average = latest.average)
      case _                                ⇒ copy(value = latest.value)
    }
    else this

  /**
   * The numerical value of the average, if defined, otherwise the latest value
   */
  def smoothValue: Double = average match {
    case Some(avg) ⇒ avg.value
    case None      ⇒ value.doubleValue
  }

  /**
   * @return true if this value is smoothed
   */
  def isSmooth: Boolean = average.isDefined

  /**
   * Returns true if <code>that</code> is tracking the same metric as this.
   */
  def sameAs(that: Metric): Boolean = name == that.name

  override def hashCode = name.##
  override def equals(obj: Any) = obj match {
    case other: Metric ⇒ sameAs(other)
    case _             ⇒ false
  }

}

/**
 * Factory for creating valid Metric instances.
 */
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
object Metric extends MetricNumericConverter {

  /**
   * Creates a new Metric instance if the value is valid, otherwise None
   * is returned. Invalid numeric values are negative and NaN/Infinite.
   */
  def create(name: String, value: Number, decayFactor: Option[Double]): Option[Metric] =
    if (defined(value)) Some(new Metric(name, value, ceateEWMA(value.doubleValue, decayFactor)))
    else None

  /**
   * Creates a new Metric instance if the Try is successful and the value is valid,
   * otherwise None is returned. Invalid numeric values are negative and NaN/Infinite.
   */
  def create(name: String, value: Try[Number], decayFactor: Option[Double]): Option[Metric] = value match {
    case Success(v) ⇒ create(name, v, decayFactor)
    case Failure(_) ⇒ None
  }

  private def ceateEWMA(value: Double, decayFactor: Option[Double]): Option[EWMA] = decayFactor match {
    case Some(alpha) ⇒ Some(EWMA(value, alpha))
    case None        ⇒ None
  }

}

/**
 * The snapshot of current sampled health metrics for any monitored process.
 * Collected and gossipped at regular intervals for dynamic cluster management strategies.
 *
 * Equality of NodeMetrics is based on its address.
 *
 * @param address [[akka.actor.Address]] of the node the metrics are gathered at
 * @param timestamp the time of sampling, in milliseconds since midnight, January 1, 1970 UTC
 * @param metrics the set of sampled [[akka.cluster.Metric]]
 */
@SerialVersionUID(1L)
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
final case class NodeMetrics(address: Address, timestamp: Long, metrics: Set[Metric] = Set.empty[Metric]) {

  /**
   * Returns the most recent data.
   */
  def merge(that: NodeMetrics): NodeMetrics = {
    require(address == that.address, s"merge only allowed for same address, [$address] != [$that.address]")
    if (timestamp >= that.timestamp) this // that is older
    else {
      // equality is based on the name of the Metric and Set doesn't replace existing element
      copy(metrics = that.metrics union metrics, timestamp = that.timestamp)
    }
  }

  def metric(key: String): Option[Metric] = metrics.collectFirst { case m if m.name == key ⇒ m }

  /**
   * Java API
   */
  def getMetrics: java.lang.Iterable[Metric] =
    scala.collection.JavaConverters.asJavaIterableConverter(metrics).asJava

  /**
   * Returns true if <code>that</code> address is the same as this
   */
  def sameAs(that: NodeMetrics): Boolean = address == that.address

  override def hashCode = address.##
  override def equals(obj: Any) = obj match {
    case other: NodeMetrics ⇒ sameAs(other)
    case _                  ⇒ false
  }

}

/**
 * Definitions of the built-in standard metrics.
 *
 * The following extractors and data structures makes it easy to consume the
 * [[akka.cluster.NodeMetrics]] in for example load balancers.
 */
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
object StandardMetrics {

  // Constants for the heap related Metric names
  final val HeapMemoryUsed = "heap-memory-used"
  final val HeapMemoryCommitted = "heap-memory-committed"
  final val HeapMemoryMax = "heap-memory-max"

  // Constants for the cpu related Metric names
  final val SystemLoadAverage = "system-load-average"
  final val Processors = "processors"
  final val CpuCombined = "cpu-combined"

  object HeapMemory {

    /**
     * Given a NodeMetrics it returns the HeapMemory data if the nodeMetrics contains
     * necessary heap metrics.
     * @return if possible a tuple matching the HeapMemory constructor parameters
     */
    def unapply(nodeMetrics: NodeMetrics): Option[(Address, Long, Long, Long, Option[Long])] = {
      for {
        used ← nodeMetrics.metric(HeapMemoryUsed)
        committed ← nodeMetrics.metric(HeapMemoryCommitted)
      } yield (nodeMetrics.address, nodeMetrics.timestamp,
        used.smoothValue.longValue, committed.smoothValue.longValue,
        nodeMetrics.metric(HeapMemoryMax).map(_.smoothValue.longValue))
    }

  }

  /**
   * Java API to extract HeapMemory data from nodeMetrics, if the nodeMetrics
   * contains necessary heap metrics, otherwise it returns null.
   */
  def extractHeapMemory(nodeMetrics: NodeMetrics): HeapMemory = nodeMetrics match {
    case HeapMemory(address, timestamp, used, committed, max) ⇒
      // note that above extractor returns tuple
      HeapMemory(address, timestamp, used, committed, max)
    case _ ⇒ null
  }

  /**
   * The amount of used and committed memory will always be <= max if max is defined.
   * A memory allocation may fail if it attempts to increase the used memory such that used > committed
   * even if used <= max is true (e.g. when the system virtual memory is low).
   *
   * @param address [[akka.actor.Address]] of the node the metrics are gathered at
   * @param timestamp the time of sampling, in milliseconds since midnight, January 1, 1970 UTC
   * @param used the current sum of heap memory used from all heap memory pools (in bytes)
   * @param committed the current sum of heap memory guaranteed to be available to the JVM
   *   from all heap memory pools (in bytes). Committed will always be greater than or equal to used.
   * @param max the maximum amount of memory (in bytes) that can be used for JVM memory management.
   *   Can be undefined on some OS.
   */
  @SerialVersionUID(1L)
  final case class HeapMemory(address: Address, timestamp: Long, used: Long, committed: Long, max: Option[Long]) {
    require(committed > 0L, "committed heap expected to be > 0 bytes")
    require(max.isEmpty || max.get > 0L, "max heap expected to be > 0 bytes")
  }

  object Cpu {

    /**
     * Given a NodeMetrics it returns the Cpu data if the nodeMetrics contains
     * necessary cpu metrics.
     * @return if possible a tuple matching the Cpu constructor parameters
     */
    def unapply(nodeMetrics: NodeMetrics): Option[(Address, Long, Option[Double], Option[Double], Int)] = {
      for {
        processors ← nodeMetrics.metric(Processors)
      } yield (nodeMetrics.address, nodeMetrics.timestamp,
        nodeMetrics.metric(SystemLoadAverage).map(_.smoothValue),
        nodeMetrics.metric(CpuCombined).map(_.smoothValue), processors.value.intValue)
    }

  }

  /**
   * Java API to extract Cpu data from nodeMetrics, if the nodeMetrics
   * contains necessary cpu metrics, otherwise it returns null.
   */
  def extractCpu(nodeMetrics: NodeMetrics): Cpu = nodeMetrics match {
    case Cpu(address, timestamp, systemLoadAverage, cpuCombined, processors) ⇒
      // note that above extractor returns tuple
      Cpu(address, timestamp, systemLoadAverage, cpuCombined, processors)
    case _ ⇒ null
  }

  /**
   * @param address [[akka.actor.Address]] of the node the metrics are gathered at
   * @param timestamp the time of sampling, in milliseconds since midnight, January 1, 1970 UTC
   * @param systemLoadAverage OS-specific average load on the CPUs in the system, for the past 1 minute,
   *    The system is possibly nearing a bottleneck if the system load average is nearing number of cpus/cores.
   * @param cpuCombined combined CPU sum of User + Sys + Nice + Wait, in percentage ([0.0 - 1.0]. This
   *   metric can describe the amount of time the CPU spent executing code during n-interval and how
   *   much more it could theoretically.
   * @param processors the number of available processors
   */
  @SerialVersionUID(1L)
  final case class Cpu(
    address: Address,
    timestamp: Long,
    systemLoadAverage: Option[Double],
    cpuCombined: Option[Double],
    processors: Int) {

    cpuCombined match {
      case Some(x) ⇒ require(0.0 <= x && x <= 1.0, s"cpuCombined must be between [0.0 - 1.0], was [$x]")
      case None    ⇒
    }

  }

}

/**
 * INTERNAL API
 *
 * Encapsulates evaluation of validity of metric values, conversion of an actual metric value to
 * a [[akka.cluster.Metric]] for consumption by subscribed cluster entities.
 */
private[cluster] trait MetricNumericConverter {

  /**
   * An defined value is neither negative nor NaN/Infinite:
   * <ul><li>JMX system load average and max heap can be 'undefined' for certain OS, in which case a -1 is returned</li>
   * <li>SIGAR combined CPU can occasionally return a NaN or Infinite (known bug)</li></ul>
   */
  def defined(value: Number): Boolean = convertNumber(value) match {
    case Left(a)  ⇒ a >= 0
    case Right(b) ⇒ !(b < 0.0 || b.isNaN || b.isInfinite)
  }

  /**
   * May involve rounding or truncation.
   */
  def convertNumber(from: Any): Either[Long, Double] = from match {
    case n: Int        ⇒ Left(n)
    case n: Long       ⇒ Left(n)
    case n: Double     ⇒ Right(n)
    case n: Float      ⇒ Right(n)
    case n: BigInt     ⇒ Left(n.longValue)
    case n: BigDecimal ⇒ Right(n.doubleValue)
    case x             ⇒ throw new IllegalArgumentException(s"Not a number [$x]")
  }

}

/**
 * Implementations of cluster system metrics extends this trait.
 */
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
trait MetricsCollector extends Closeable {
  /**
   * Samples and collects new data points.
   * This method is invoked periodically and should return
   * current metrics for this node.
   */
  def sample(): NodeMetrics
}

/**
 * Loads JVM and system metrics through JMX monitoring beans.
 *
 * @param address The [[akka.actor.Address]] of the node being sampled
 * @param decay how quickly the exponential weighting of past data is decayed
 */
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
class JmxMetricsCollector(address: Address, decayFactor: Double) extends MetricsCollector {
  import StandardMetrics._

  private def this(cluster: Cluster) =
    this(cluster.selfAddress,
      EWMA.alpha(cluster.settings.MetricsMovingAverageHalfLife, cluster.settings.MetricsInterval))

  /**
   * This constructor is used when creating an instance from configured FQCN
   */
  def this(system: ActorSystem) = this(Cluster(system))

  private val decayFactorOption = Some(decayFactor)

  private val memoryMBean: MemoryMXBean = ManagementFactory.getMemoryMXBean

  private val osMBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean

  /**
   * Samples and collects new data points.
   * Creates a new instance each time.
   */
  def sample(): NodeMetrics = NodeMetrics(address, newTimestamp, metrics)

  def metrics: Set[Metric] = {
    val heap = heapMemoryUsage
    Set(systemLoadAverage, heapUsed(heap), heapCommitted(heap), heapMax(heap), processors).flatten
  }

  /**
   * JMX Returns the OS-specific average load on the CPUs in the system, for the past 1 minute.
   * On some systems the JMX OS system load average may not be available, in which case a -1 is
   * returned from JMX, and None is returned from this method.
   * Creates a new instance each time.
   */
  def systemLoadAverage: Option[Metric] = Metric.create(
    name = SystemLoadAverage,
    value = osMBean.getSystemLoadAverage,
    decayFactor = None)

  /**
   * (JMX) Returns the number of available processors
   * Creates a new instance each time.
   */
  def processors: Option[Metric] = Metric.create(
    name = Processors,
    value = osMBean.getAvailableProcessors,
    decayFactor = None)

  /**
   * Current heap to be passed in to heapUsed, heapCommitted and heapMax
   */
  def heapMemoryUsage: MemoryUsage = memoryMBean.getHeapMemoryUsage

  /**
   * (JMX) Returns the current sum of heap memory used from all heap memory pools (in bytes).
   * Creates a new instance each time.
   */
  def heapUsed(heap: MemoryUsage): Option[Metric] = Metric.create(
    name = HeapMemoryUsed,
    value = heap.getUsed,
    decayFactor = decayFactorOption)

  /**
   * (JMX) Returns the current sum of heap memory guaranteed to be available to the JVM
   * from all heap memory pools (in bytes).
   * Creates a new instance each time.
   */
  def heapCommitted(heap: MemoryUsage): Option[Metric] = Metric.create(
    name = HeapMemoryCommitted,
    value = heap.getCommitted,
    decayFactor = decayFactorOption)

  /**
   * (JMX) Returns the maximum amount of memory (in bytes) that can be used
   * for JVM memory management. If not defined the metrics value is None, i.e.
   * never negative.
   * Creates a new instance each time.
   */
  def heapMax(heap: MemoryUsage): Option[Metric] = Metric.create(
    name = HeapMemoryMax,
    value = heap.getMax,
    decayFactor = None)

  override def close(): Unit = ()

}

/**
 * Loads metrics through Hyperic SIGAR and JMX monitoring beans. This
 * loads wider and more accurate range of metrics compared to JmxMetricsCollector
 * by using SIGAR's native OS library.
 *
 * The constructor will by design throw exception if org.hyperic.sigar.Sigar can't be loaded, due
 * to missing classes or native libraries.
 *
 * @param address The [[akka.actor.Address]] of the node being sampled
 * @param decay how quickly the exponential weighting of past data is decayed
 * @param sigar the org.hyperic.Sigar instance
 */
@deprecated("Superseded by akka.cluster.metrics (in akka-cluster-metrics jar)", "2.4")
class SigarMetricsCollector(address: Address, decayFactor: Double, sigar: AnyRef)
  extends JmxMetricsCollector(address, decayFactor) {

  import StandardMetrics._

  private def this(cluster: Cluster) =
    this(cluster.selfAddress,
      EWMA.alpha(cluster.settings.MetricsMovingAverageHalfLife, cluster.settings.MetricsInterval),
      cluster.system.dynamicAccess.createInstanceFor[AnyRef]("org.hyperic.sigar.Sigar", Nil).get)

  /**
   * This constructor is used when creating an instance from configured FQCN
   */
  def this(system: ActorSystem) = this(Cluster(system))

  private val decayFactorOption = Some(decayFactor)

  private val EmptyClassArray: Array[(Class[_])] = Array.empty[(Class[_])]
  private val LoadAverage: Option[Method] = createMethodFrom(sigar, "getLoadAverage")
  private val Cpu: Option[Method] = createMethodFrom(sigar, "getCpuPerc")
  private val CombinedCpu: Option[Method] = Try(Cpu.get.getReturnType.getMethod("getCombined")).toOption

  // Do something initially, in constructor, to make sure that the native library can be loaded.
  // This will by design throw exception if sigar isn't usable
  val pid: Long = createMethodFrom(sigar, "getPid") match {
    case Some(method) ⇒
      try method.invoke(sigar).asInstanceOf[Long] catch {
        case e: InvocationTargetException if e.getCause.isInstanceOf[LinkageError] ⇒
          // native libraries not in place
          // don't throw fatal LinkageError, but something harmless
          throw new IllegalArgumentException(e.getCause.toString)
        case e: InvocationTargetException ⇒ throw e.getCause
      }
    case None ⇒ throw new IllegalArgumentException("Wrong version of Sigar, expected 'getPid' method")
  }

  override def metrics: Set[Metric] = {
    super.metrics.filterNot(_.name == SystemLoadAverage) union Set(systemLoadAverage, cpuCombined).flatten
  }

  /**
   * (SIGAR / JMX) Returns the OS-specific average load on the CPUs in the system, for the past 1 minute.
   * On some systems the JMX OS system load average may not be available, in which case a -1 is returned
   * from JMX, which means that None is returned from this method.
   * Hyperic SIGAR provides more precise values, thus, if the library is on the classpath, it is the default.
   * Creates a new instance each time.
   */
  override def systemLoadAverage: Option[Metric] = Metric.create(
    name = SystemLoadAverage,
    value = Try(LoadAverage.get.invoke(sigar).asInstanceOf[Array[AnyRef]](0).asInstanceOf[Number]),
    decayFactor = None) orElse super.systemLoadAverage

  /**
   * (SIGAR) Returns the combined CPU sum of User + Sys + Nice + Wait, in percentage. This metric can describe
   * the amount of time the CPU spent executing code during n-interval and how much more it could
   * theoretically. Note that 99% CPU utilization can be optimal or indicative of failure.
   *
   * In the data stream, this will sometimes return with a valid metric value, and sometimes as a NaN or Infinite.
   * Documented bug https://bugzilla.redhat.com/show_bug.cgi?id=749121 and several others.
   *
   * Creates a new instance each time.
   */
  def cpuCombined: Option[Metric] = Metric.create(
    name = CpuCombined,
    value = Try(CombinedCpu.get.invoke(Cpu.get.invoke(sigar)).asInstanceOf[Number]),
    decayFactor = decayFactorOption)

  /**
   * Releases any native resources associated with this instance.
   */
  override def close(): Unit = Try(createMethodFrom(sigar, "close").get.invoke(sigar))

  private def createMethodFrom(ref: AnyRef, method: String, types: Array[(Class[_])] = EmptyClassArray): Option[Method] =
    Try(ref.getClass.getMethod(method, types: _*)).toOption

}

/**
 * INTERNAL API
 * Factory to create configured MetricsCollector.
 * If instantiation of SigarMetricsCollector fails (missing class or native library)
 * it falls back to use JmxMetricsCollector.
 */
private[cluster] object MetricsCollector {
  def apply(system: ExtendedActorSystem, settings: ClusterSettings): MetricsCollector = {
    import settings.{ MetricsCollectorClass ⇒ fqcn }
    def log = Logging(system, getClass.getName)
    if (fqcn == classOf[SigarMetricsCollector].getName) {
      Try(new SigarMetricsCollector(system)) match {
        case Success(sigarCollector) ⇒ sigarCollector
        case Failure(e) ⇒
          Cluster(system).InfoLogger.logInfo(
            "Metrics will be retreived from MBeans, and may be incorrect on some platforms. " +
              "To increase metric accuracy add the 'sigar.jar' to the classpath and the appropriate " +
              "platform-specific native libary to 'java.library.path'. Reason: " +
              e.toString)
          new JmxMetricsCollector(system)
      }

    } else {
      system.dynamicAccess.createInstanceFor[MetricsCollector](fqcn, List(classOf[ActorSystem] -> system)).
        recover {
          case e ⇒ throw new ConfigurationException("Could not create custom metrics collector [" + fqcn + "] due to:" + e.toString)
        }.get
    }
  }
}

