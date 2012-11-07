/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.io.Closeable
import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }
import java.lang.management.{ OperatingSystemMXBean, MemoryMXBean, ManagementFactory }
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

import scala.collection.immutable.{ SortedSet, Map }
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.runtime.{ RichLong, RichDouble, RichInt }
import scala.util.{ Try, Success, Failure }

import akka.ConfigurationException
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.DynamicAccess
import akka.actor.ExtendedActorSystem
import akka.cluster.MemberStatus.Up
import akka.event.Logging

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
 * Calculation of statistical data for each monitored process is delegated to the
 * [[akka.cluster.DataStream]] for exponential smoothing, with additional decay factor.
 */
private[cluster] class ClusterMetricsCollector(publisher: ActorRef) extends Actor with ActorLogging {

  import InternalClusterAction._
  import ClusterEvent._
  import Member.addressOrdering
  import context.dispatcher
  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, scheduler, settings }
  import settings._

  /**
   * The node ring gossipped that contains only members that are Up.
   */
  var nodes: SortedSet[Address] = SortedSet.empty

  /**
   * The latest metric values with their statistical data.
   */
  var latestGossip: MetricsGossip = MetricsGossip()

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
    cluster.subscribe(self, classOf[MemberEvent])
    log.info("Metrics collection has started successfully on node [{}]", selfAddress)
  }

  def receive = {
    case GossipTick                 ⇒ gossip()
    case MetricsTick                ⇒ collect()
    case state: CurrentClusterState ⇒ receiveState(state)
    case MemberUp(m)                ⇒ receiveMember(m)
    case e: MemberEvent             ⇒ removeMember(e)
    case msg: MetricsGossipEnvelope ⇒ receiveGossip(msg)
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
  def receiveMember(member: Member): Unit = nodes += member.address

  /**
   * Removes a member from the member node ring.
   */
  def removeMember(event: MemberEvent): Unit = {
    nodes -= event.member.address
    latestGossip = latestGossip remove event.member.address
    publish()
  }

  /**
   * Updates the initial node ring for those nodes that are [[akka.cluster.MemberStatus.Up]].
   */
  def receiveState(state: CurrentClusterState): Unit = nodes = state.members collect { case m if m.status == Up ⇒ m.address }

  /**
   * Samples the latest metrics for the node, updates metrics statistics in
   * [[akka.cluster.MetricsGossip]], and publishes the change to the event bus.
   *
   * @see [[akka.cluster.ClusterMetricsCollector.collect( )]]
   */
  def collect(): Unit = {
    latestGossip :+= collector.sample
    publish()
  }

  /**
   * Receives changes from peer nodes, merges remote with local gossip nodes, then publishes
   * changes to the event stream for load balancing router consumption, and gossips to peers.
   */
  def receiveGossip(envelope: MetricsGossipEnvelope): Unit = {
    val remoteGossip = envelope.gossip

    if (remoteGossip != latestGossip) {
      latestGossip = latestGossip merge remoteGossip
      publish()
      gossipTo(envelope.from)
    }
  }

  /**
   * Gossip to peer nodes.
   */
  def gossip(): Unit = selectRandomNode((nodes - selfAddress).toIndexedSeq) foreach gossipTo

  def gossipTo(address: Address): Unit =
    context.actorFor(self.path.toStringWithAddress(address)) ! MetricsGossipEnvelope(selfAddress, latestGossip)

  def selectRandomNode(addresses: IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  /**
   * Publishes to the event stream.
   */
  def publish(): Unit = publisher ! PublishEvent(ClusterMetricsChanged(latestGossip.nodes))

}

/**
 * INTERNAL API
 *
 * @param nodes metrics per node
 */
private[cluster] case class MetricsGossip(nodes: Set[NodeMetrics] = Set.empty) {

  /**
   * Removes nodes if their correlating node ring members are not [[akka.cluster.MemberStatus.Up]]
   */
  def remove(node: Address): MetricsGossip = copy(nodes = nodes filterNot (_.address == node))

  /**
   * Adds new remote [[akka.cluster.NodeMetrics]] and merges existing from a remote gossip.
   */
  def merge(remoteGossip: MetricsGossip): MetricsGossip = {
    val remoteNodes = remoteGossip.nodes.map(n ⇒ n.address -> n).toMap
    val toMerge = nodeKeys intersect remoteNodes.keySet
    val onlyInRemote = remoteNodes.keySet -- nodeKeys
    val onlyInLocal = nodeKeys -- remoteNodes.keySet

    val seen = nodes.collect {
      case n if toMerge contains n.address     ⇒ n merge remoteNodes(n.address)
      case n if onlyInLocal contains n.address ⇒ n
    }

    val unseen = remoteGossip.nodes.collect { case n if onlyInRemote contains n.address ⇒ n }

    copy(nodes = seen ++ unseen)
  }

  /**
   * Adds new local [[akka.cluster.NodeMetrics]] and initializes the data, or merges an existing.
   */
  def :+(data: NodeMetrics): MetricsGossip = {
    val previous = metricsFor(data)
    val names = previous map (_.name)

    val (toMerge: Set[Metric], unseen: Set[Metric]) = data.metrics partition (a ⇒ names contains a.name)
    val merged = toMerge flatMap (latest ⇒ previous.collect { case peer if latest same peer ⇒ peer :+ latest })

    val refreshed = nodes filterNot (_.address == data.address)
    copy(nodes = refreshed + data.copy(metrics = unseen ++ merged))
  }

  /**
   * Returns a set of [[akka.actor.Address]] for a given node set.
   */
  def nodeKeys: Set[Address] = nodes map (_.address)

  /**
   * Returns metrics for a node if exists.
   */
  def metricsFor(node: NodeMetrics): Set[Metric] = nodes flatMap (n ⇒ if (n same node) n.metrics else Set.empty[Metric])

}

/**
 * INTERNAL API
 * Envelope adding a sender address to the gossip.
 */
private[cluster] case class MetricsGossipEnvelope(from: Address, gossip: MetricsGossip) extends ClusterMessage

/**
 * The exponentially weighted moving average (EWMA) approach captures short-term
 * movements in volatility for a conditional volatility forecasting model. By virtue
 * of its alpha, or decay factor, this provides a statistical streaming data model
 * that is exponentially biased towards newer entries.
 *
 * An EWMA only needs the most recent forecast value to be kept, as opposed to a standard
 * moving average model.
 *
 * INTERNAL API
 *
 * @param decay sets how quickly the exponential weighting decays for past data compared to new data
 *              Corresponds to 'N time periods' as explained in
 *              http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average
 *
 * @param ewma the current exponentially weighted moving average, e.g. Y(n - 1), or,
 *             the sampled value resulting from the previous smoothing iteration.
 *             This value is always used as the previous EWMA to calculate the new EWMA.
 *
 * @param timestamp the most recent time of sampling
 *
 * @param startTime the time of initial sampling for this data stream
 */
private[cluster] case class DataStream(decay: Int, ewma: Double, startTime: Long, timestamp: Long)
  extends ClusterMessage {

  require(decay >= 1, "Rate of decay must be >= 1")

  /**
   * The rate at which the weights of past observations
   * decay as they become more distant.
   */
  private val α = 2.0 / (decay + 1)

  /**
   * Calculates the exponentially weighted moving average for a given monitored data set.
   *
   * @param xn the new data point
   *
   * @return a [[akka.cluster.DataStream]] with the updated yn and timestamp
   */
  def :+(xn: Double): DataStream = copy(ewma = (α * xn) + (1 - α) * ewma, timestamp = newTimestamp)

  /**
   * The duration of observation for this data stream
   */
  def duration: FiniteDuration = (timestamp - startTime).millis

}

/**
 * INTERNAL API
 *
 * @param name the metric name
 *
 * @param value the metric value, which may or may not be defined, it must be a valid numerical value,
 *   see [[akka.cluster.MetricNumericConverter.defined()]]
 *
 * @param average the data stream of the metric value, for trending over time. Metrics that are already
 *                averages (e.g. system load average) or finite (e.g. as total cores), are not trended.
 */
private[cluster] case class Metric private (name: String, value: Option[Number], average: Option[DataStream],
                                            dummy: Boolean) // dummy because of overloading clash with apply
  extends ClusterMessage with MetricNumericConverter {

  require(value.isEmpty || defined(value.get), "Invalid Metric [%s] value [%]".format(name, value))

  /**
   * If defined ( [[akka.cluster.MetricNumericConverter.defined()]] ), updates the new
   * data point, and if defined, updates the data stream. Returns the updated metric.
   */
  def :+(latest: Metric): Metric = latest.value match {
    case Some(v) if this same latest ⇒ average match {
      case Some(previous)                   ⇒ copy(value = latest.value, average = Some(previous :+ v.doubleValue))
      case None if latest.average.isDefined ⇒ copy(value = latest.value, average = latest.average)
      case None if latest.average.isEmpty   ⇒ copy(value = latest.value)
    }
    case None ⇒ this
  }

  def isDefined: Boolean = value.isDefined

  /**
   * The numerical value of the average, if defined, otherwise the latest value,
   * if defined.
   */
  def averageValue: Option[Double] =
    average map (_.ewma) orElse value.map(_.doubleValue)

  /**
   * Returns true if <code>that</code> is tracking the same metric as this.
   */
  def same(that: Metric): Boolean = name == that.name

}

/**
 * INTERNAL API
 *
 * Companion object of Metric class.
 */
private[cluster] object Metric extends MetricNumericConverter {
  import NodeMetrics.MetricValues._

  private def definedValue(value: Option[Number]): Option[Number] =
    if (value.isDefined && defined(value.get)) value else None

  /**
   * Evaluates validity of <code>value</code> based on whether it is available (SIGAR on classpath)
   * or defined for the OS (JMX). If undefined we set the value option to None and do not modify
   * the latest sampled metric to avoid skewing the statistical trend.
   *
   * @param decay rate of decay for values applicable for trending
   */
  def apply(name: String, value: Option[Number], decay: Option[Int]): Metric = {
    val dv = definedValue(value)
    val average =
      if (decay.isDefined && dv.isDefined) {
        val now = newTimestamp
        Some(DataStream(decay.get, dv.get.doubleValue, now, now))
      } else
        None
    new Metric(name, dv, average, true)
  }

  def apply(name: String): Metric = new Metric(name, None, None, true)
}

/**
 * INTERNAL API
 *
 * The snapshot of current sampled health metrics for any monitored process.
 * Collected and gossipped at regular intervals for dynamic cluster management strategies.
 *
 * For the JVM memory. The amount of used and committed memory will always be <= max if max is defined.
 * A memory allocation may fail if it attempts to increase the used memory such that used > committed
 * even if used <= max is true (e.g. when the system virtual memory is low).
 *
 * The system is possibly nearing a bottleneck if the system load average is nearing in cpus/cores.
 *
 * @param address [[akka.actor.Address]] of the node the metrics are gathered at
 *
 * @param timestamp the time of sampling
 *
 * @param metrics the array of sampled [[akka.actor.Metric]]
 */
private[cluster] case class NodeMetrics(address: Address, timestamp: Long, metrics: Set[Metric] = Set.empty[Metric]) extends ClusterMessage {
  import NodeMetrics._
  import NodeMetrics.MetricValues._

  /**
   * Returns the most recent data.
   */
  def merge(that: NodeMetrics): NodeMetrics = if (this updatable that) copy(metrics = that.metrics, timestamp = that.timestamp) else this

  /**
   * Returns true if <code>that</code> address is the same as this and its metric set is more recent.
   */
  def updatable(that: NodeMetrics): Boolean = (this same that) && (that.timestamp > timestamp)

  /**
   * Returns true if <code>that</code> address is the same as this
   */
  def same(that: NodeMetrics): Boolean = address == that.address

  /**
   * Of all the data streams, this fluctuates the most.
   */
  def heapMemory: HeapMemory = HeapMemory(metric(HeapMemoryUsed), metric(HeapMemoryCommitted), metric(HeapMemoryMax))

  def networkLatency: NetworkLatency = NetworkLatency(metric(NetworkInboundRate), metric(NetworkOutboundRate))

  def cpu: Cpu = Cpu(metric(SystemLoadAverage), metric(Processors), metric(CpuCombined), metric(TotalCores))

  def metric(key: String): Metric = metrics.collectFirst { case m if m.name == key ⇒ m } getOrElse Metric(key)

}

/**
 * INTERNAL API
 *
 * Companion object of Metric class - used by metrics consumers such as the load balancing routers.
 *
 * The following extractors and orderings hide the implementation from cluster metric consumers
 * such as load balancers.
 */
private[cluster] object NodeMetrics {

  sealed trait MetricValues

  object MetricValues {

    final val HeapMemoryUsed = "heap-memory-used"
    final val HeapMemoryCommitted = "heap-memory-committed"
    final val HeapMemoryMax = "heap-memory-max"
    final val NetworkInboundRate = "network-max-inbound"
    final val NetworkOutboundRate = "network-max-outbound"
    final val SystemLoadAverage = "system-load-average"
    final val Processors = "processors"
    final val CpuCombined = "cpu-combined"
    final val TotalCores = "total-cores"

    def unapply(v: HeapMemory): Tuple3[Long, Long, Option[Long]] =
      (v.used.averageValue.get.longValue,
        v.committed.averageValue.get.longValue,
        v.max.averageValue map (_.longValue) orElse None)

    def unapply(v: NetworkLatency): Option[(Long, Long)] =
      (v.inbound.averageValue, v.outbound.averageValue) match {
        case (Some(a), Some(b)) ⇒ Some((a.longValue, b.longValue))
        case _                  ⇒ None
      }

    def unapply(v: Cpu): Tuple4[Option[Double], Int, Option[Double], Option[Int]] =
      (v.systemLoadAverage.averageValue map (_.doubleValue),
        v.processors.averageValue.get.intValue,
        v.combinedCpu.averageValue map (_.doubleValue),
        v.cores.averageValue map (_.intValue))
  }

  /**
   * @param used the current sum of heap memory used from all heap memory pools (in bytes)
   *
   * @param committed the current sum of heap memory guaranteed to be available to the JVM
   * from all heap memory pools (in bytes). Committed will always be greater than or equal to used.
   *
   * @param max the maximum amount of memory (in bytes) that can be used for JVM memory management.
   *            Can be undefined on some OS.
   */
  case class HeapMemory(used: Metric, committed: Metric, max: Metric) extends MetricValues {
    require(used.isDefined, "used must be defined")
    require(committed.isDefined, "committed must be defined")
  }

  /**
   * @param inbound the inbound network IO rate, in bytes
   *
   * @param outbound the outbound network IO rate, in bytes
   */
  case class NetworkLatency(inbound: Metric, outbound: Metric) extends MetricValues

  /**
   * @param systemLoadAverage OS-specific average load on the CPUs in the system, for the past 1 minute
   *
   * @param processors the number of available processors
   *
   * @param combinedCpu combined CPU sum of User + Sys + Nice + Wait, in percentage. This metric can describe
   * the amount of time the CPU spent executing code during n-interval and how much more it could theoretically.
   *
   * @param cores the number of cores (multi-core: per processor)
   */
  private[cluster] case class Cpu(systemLoadAverage: Metric, processors: Metric, combinedCpu: Metric, cores: Metric) extends MetricValues {
    require(processors.isDefined, "processors must be defined")
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
   * An undefined value is neither a -1 (negative) or NaN/Infinite:
   * <ul><li>JMX system load average and max heap can be 'undefined' for certain OS, in which case a -1 is returned</li>
   * <li>SIGAR combined CPU can occasionally return a NaN or Infinite (known bug)</li></ul>
   */
  def defined(value: Number): Boolean = convertNumber(value) match {
    case Left(a)  ⇒ a >= 0
    case Right(b) ⇒ !(b.isNaN || b.isInfinite)
  }

  /**
   * May involve rounding or truncation.
   */
  def convertNumber(from: Any): Either[Long, Double] = from match {
    case n: Int        ⇒ Left(n)
    case n: Long       ⇒ Left(n)
    case n: Double     ⇒ Right(n)
    case n: Float      ⇒ Right(n)
    case n: RichInt    ⇒ Left(n.abs)
    case n: RichLong   ⇒ Left(n.self)
    case n: RichDouble ⇒ Right(n.self)
    case n: BigInt     ⇒ Left(n.longValue)
    case n: BigDecimal ⇒ Right(n.doubleValue)
    case x             ⇒ throw new IllegalArgumentException("Not a number [%s]" format x)
  }

}

/**
 * INTERNAL API
 */
private[cluster] trait MetricsCollector extends Closeable {
  /**
   * Samples and collects new data points.
   */
  def sample: NodeMetrics
}

/**
 * INTERNAL API
 *
 * Loads JVM metrics through JMX monitoring beans.
 *
 * @param address The [[akka.actor.Address]] of the node being sampled
 * @param decay how quickly the exponential weighting of past data is decayed
 */
private[cluster] class JmxMetricsCollector(address: Address, decay: Int) extends MetricsCollector {
  import NodeMetrics.MetricValues._

  private def this(cluster: Cluster) =
    this(cluster.selfAddress, cluster.settings.MetricsRateOfDecay)

  def this(system: ActorSystem) = this(Cluster(system))

  private val decayOption = Some(decay)

  private val memoryMBean: MemoryMXBean = ManagementFactory.getMemoryMXBean

  private val osMBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean

  /**
   * Samples and collects new data points.
   */
  def sample: NodeMetrics = NodeMetrics(address, newTimestamp, Set(
    systemLoadAverage, heapUsed, heapCommitted, heapMax, processors))

  /**
   * JMX Returns the OS-specific average load on the CPUs in the system, for the past 1 minute.
   * On some systems the JMX OS system load average may not be available, in which case a -1 is returned,
   * which means that the returned Metric is undefined.
   */
  def systemLoadAverage: Metric = Metric(
    name = SystemLoadAverage,
    value = Some(osMBean.getSystemLoadAverage),
    decay = None)

  /**
   * (JMX) Returns the number of available processors
   */
  def processors: Metric = Metric(
    name = Processors,
    value = Some(osMBean.getAvailableProcessors),
    decay = None)

  // FIXME those three heap calls should be done at once

  /**
   * (JMX) Returns the current sum of heap memory used from all heap memory pools (in bytes).
   */
  def heapUsed: Metric = Metric(
    name = HeapMemoryUsed,
    value = Some(memoryMBean.getHeapMemoryUsage.getUsed),
    decay = decayOption)

  /**
   * (JMX) Returns the current sum of heap memory guaranteed to be available to the JVM
   * from all heap memory pools (in bytes).
   */
  def heapCommitted: Metric = Metric(
    name = HeapMemoryCommitted,
    value = Some(memoryMBean.getHeapMemoryUsage.getCommitted),
    decay = decayOption)

  /**
   * (JMX) Returns the maximum amount of memory (in bytes) that can be used
   * for JVM memory management. If not defined the metrics value is None, i.e.
   * never negative.
   */
  def heapMax: Metric = Metric(
    name = HeapMemoryMax,
    value = Some(memoryMBean.getHeapMemoryUsage.getMax),
    decay = None)

  def close(): Unit = ()

}

/**
 * INTERNAL API
 *
 * Loads metrics through Hyperic SIGAR and JMX monitoring beans. This
 * loads wider and more accurate range of metrics compared to JmxMetricsCollector
 * by using SIGAR's native OS library.
 *
 * The constructor will by design throw exception if org.hyperic.sigar.Sigar can't be loaded, due
 * to missing classes or native libraries.
 *
 * TODO switch to Scala reflection
 *
 * @param address The [[akka.actor.Address]] of the node being sampled
 * @param decay how quickly the exponential weighting of past data is decayed
 * @param sigar the org.hyperic.Sigar instance
 */
private[cluster] class SigarMetricsCollector(address: Address, decay: Int, sigar: AnyRef)
  extends JmxMetricsCollector(address, decay) {
  import NodeMetrics.MetricValues._

  def this(address: Address, decay: Int, dynamicAccess: DynamicAccess) =
    this(address, decay, dynamicAccess.createInstanceFor[AnyRef]("org.hyperic.sigar.Sigar", Seq.empty).get)

  private def this(cluster: Cluster) =
    this(cluster.selfAddress, cluster.settings.MetricsRateOfDecay, cluster.system.dynamicAccess)

  def this(system: ActorSystem) = this(Cluster(system))

  private val decayOption = Some(decay)

  private val LoadAverage: Option[Method] = createMethodFrom(sigar, "getLoadAverage")

  private val CpuList: Option[Method] = createMethodFrom(sigar, "getCpuInfoList").map(m ⇒ m)

  private val NetInterfaces: Option[Method] = createMethodFrom(sigar, "getNetInterfaceList")

  private val Cpu: Option[Method] = createMethodFrom(sigar, "getCpuPerc")

  private val CombinedCpu: Option[Method] = Try(Cpu.get.getReturnType.getMethod("getCombined")).toOption

  private val Pid: Option[Method] = createMethodFrom(sigar, "getPid")

  // Do something initially, in constructor, to make sure that the native library can be loaded.
  // This will by design throw exception if sigar isn't usable
  val pid: Long = createMethodFrom(sigar, "getPid") match {
    case Some(method) ⇒
      try method.invoke(sigar).asInstanceOf[Long] catch {
        case e: InvocationTargetException if e.getCause.isInstanceOf[LinkageError] ⇒
          // native libraries not in place
          // don't throw fatal LinkageError, but something less harmless
          throw new IllegalArgumentException(e.getCause.toString)
        case e: InvocationTargetException ⇒ throw e.getCause
      }
    case None ⇒ throw new IllegalArgumentException("Wrong version of Sigar, expected 'getPid' method")
  }

  /**
   * Samples and collects new data points.
   */
  override def sample: NodeMetrics = NodeMetrics(address, newTimestamp, Set(cpuCombined, totalCores,
    systemLoadAverage, heapUsed, heapCommitted, heapMax, processors, networkMaxRx, networkMaxTx))

  /**
   * (SIGAR / JMX) Returns the OS-specific average load on the CPUs in the system, for the past 1 minute.
   * On some systems the JMX OS system load average may not be available, in which case a -1 is returned,
   * which means that the returned Metric is undefined.
   * Hyperic SIGAR provides more precise values, thus, if the library is on the classpath, it is the default.
   */
  override def systemLoadAverage: Metric = {
    val m = Metric(
      name = SystemLoadAverage,
      value = Try(LoadAverage.get.invoke(sigar).asInstanceOf[Array[AnyRef]].head.asInstanceOf[Number]).toOption,
      decay = None)
    if (m.isDefined) m else super.systemLoadAverage
  }

  /**
   * (SIGAR) Returns the combined CPU sum of User + Sys + Nice + Wait, in percentage. This metric can describe
   * the amount of time the CPU spent executing code during n-interval and how much more it could
   * theoretically. Note that 99% CPU utilization can be optimal or indicative of failure.
   *
   * In the data stream, this will sometimes return with a valid metric value, and sometimes as a NaN or Infinite.
   * Documented bug https://bugzilla.redhat.com/show_bug.cgi?id=749121 and several others.
   */
  def cpuCombined: Metric = Metric(
    name = CpuCombined,
    value = Try(CombinedCpu.get.invoke(Cpu.get.invoke(sigar)).asInstanceOf[Number]).toOption,
    decay = decayOption)

  /**
   * FIXME: Array[Int].head - expose all if cores per processor might differ.
   * (SIGAR) Returns the total number of cores.
   *
   * FIXME do we need this information, isn't it enough with jmx processors?
   */
  def totalCores: Metric = Metric(
    name = TotalCores,
    value = Try(CpuList.get.invoke(sigar).asInstanceOf[Array[AnyRef]].map(cpu ⇒
      createMethodFrom(cpu, "getTotalCores").get.invoke(cpu).asInstanceOf[Number]).head).toOption,
    decay = None)

  // FIXME those two network calls should be combined into one

  /**
   * (SIGAR) Returns the max network IO read/write value, in bytes, for network latency evaluation.
   */
  def networkMaxRx: Metric = networkFor("getRxBytes", NetworkInboundRate)

  /**
   * (SIGAR) Returns the max network IO outbound value, in bytes.
   */
  def networkMaxTx: Metric = networkFor("getTxBytes", NetworkOutboundRate)

  /**
   * Releases any native resources associated with this instance.
   */
  override def close(): Unit = Try(createMethodFrom(sigar, "close").get.invoke(sigar))

  // FIXME network metrics needs thought and refactoring

  /**
   * Returns the max bytes for the given <code>method</code> in metric for <code>metric</code> from the network interface stats.
   */
  private def networkFor(method: String, metric: String): Metric = Metric(
    name = metric,
    value = Try(networkStats.collect {
      case (_, a) ⇒
        createMethodFrom(a, method).get.invoke(a).asInstanceOf[Long]
    }.max.asInstanceOf[Number]).toOption.orElse(None),
    decay = decayOption)

  /**
   * Returns the network stats per interface.
   */
  private def networkStats: Map[String, AnyRef] = Try(NetInterfaces.get.invoke(sigar).asInstanceOf[Array[String]].map(arg ⇒
    arg -> (createMethodFrom(sigar, "getNetInterfaceStat", Array(classOf[String])).get.invoke(sigar, arg))).toMap) getOrElse Map.empty[String, AnyRef]

  private def createMethodFrom(ref: AnyRef, method: String, types: Array[(Class[_])] = Array.empty[(Class[_])]): Option[Method] =
    Try(ref.getClass.getMethod(method, types: _*)).toOption

}

/**
 * INTERNAL API
 * Companion object of MetricsCollector class.
 */
private[cluster] object MetricsCollector {
  def apply(system: ExtendedActorSystem, settings: ClusterSettings): MetricsCollector = {
    import settings.{ MetricsCollectorClass ⇒ fqcn }
    def log = Logging(system, "MetricsCollector")
    if (fqcn == classOf[SigarMetricsCollector].getName) {
      Try(new SigarMetricsCollector(system)) match {
        case Success(sigarCollector) ⇒ sigarCollector
        case Failure(e) ⇒
          log.info("Metrics will be retreived from MBeans, and may be incorrect on some platforms. " +
            "To increase metric accuracy add the 'sigar.jar' to the classpath and the appropriate " +
            "platform-specific native libary to 'java.library.path'. Reason: " +
            e.toString)
          new JmxMetricsCollector(system)
      }

    } else {
      system.dynamicAccess.createInstanceFor[MetricsCollector](
        fqcn, Seq(classOf[ActorSystem] -> system)).recover({
          case e ⇒ throw new ConfigurationException("Could not create custom metrics collector [" + fqcn + "] due to:" + e.toString)
        }).get
    }
  }
}

