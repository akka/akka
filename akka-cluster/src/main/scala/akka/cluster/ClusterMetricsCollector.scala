/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.collection.immutable.{ SortedSet, Map }
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.{ Try, Success, Failure }
import scala.math.ScalaNumericConversions
import scala.runtime.{ RichLong, RichDouble, RichInt }

import akka.actor._
import akka.event.LoggingAdapter
import akka.cluster.MemberStatus.Up

import java.lang.management.{ OperatingSystemMXBean, MemoryMXBean, ManagementFactory }
import java.lang.reflect.Method
import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }

/**
 * INTERNAL API.
 *
 * This strategy is primarily for load-balancing of nodes. It controls metrics sampling
 * at a regular frequency, prepares highly variable data for further analysis by other entities,
 * and publishes the latest cluster metrics data around the node ring to assist in determining
 * the need to redirect traffic to the least-loaded nodes.
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
  var latestGossip: MetricsGossip = MetricsGossip(MetricsRateOfDecay)

  /**
   * The metrics collector that samples data on the node.
   */
  val collector: MetricsCollector = MetricsCollector(selfAddress, log, context.system.asInstanceOf[ExtendedActorSystem].dynamicAccess)

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
private[cluster] case class MetricsGossip(rateOfDecay: Int, nodes: Set[NodeMetrics] = Set.empty) {

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
    val initialized = unseen.map(_.initialize(rateOfDecay))
    val merged = toMerge flatMap (latest ⇒ previous.collect { case peer if latest same peer ⇒ peer :+ latest })

    val refreshed = nodes filterNot (_.address == data.address)
    copy(nodes = refreshed + data.copy(metrics = initialized ++ merged))
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
 *
 * @param ewma the current exponentially weighted moving average, e.g. Y(n - 1), or,
 *             the sampled value resulting from the previous smoothing iteration.
 *             This value is always used as the previous EWMA to calculate the new EWMA.
 *
 * @param timestamp the most recent time of sampling
 *
 * @param startTime the time of initial sampling for this data stream
 */
private[cluster] case class DataStream(decay: Int, ewma: ScalaNumericConversions, startTime: Long, timestamp: Long)
  extends ClusterMessage with MetricNumericConverter {

  /**
   * The rate at which the weights of past observations
   * decay as they become more distant.
   */
  private val α = 2 / decay + 1

  /**
   * Calculates the exponentially weighted moving average for a given monitored data set.
   * The datam can be too large to fit into an int or long, thus we use ScalaNumericConversions,
   * and defer to BigInt or BigDecimal.
   *
   * @param xn the new data point
   * @return an new [[akka.cluster.DataStream]] with the updated yn and timestamp
   */
  def :+(xn: ScalaNumericConversions): DataStream = convert(xn) fold (
    nl ⇒ copy(ewma = BigInt(α * nl + 1 - α * ewma.longValue()), timestamp = newTimestamp),
    nd ⇒ copy(ewma = BigDecimal(α * nd + 1 - α * ewma.doubleValue()), timestamp = newTimestamp))

  /**
   * The duration of observation for this data stream
   */
  def duration: FiniteDuration = (timestamp - startTime) millis

}

/**
 * INTERNAL API
 *
 * Companion object of DataStream class.
 */
private[cluster] object DataStream {

  def apply(decay: Int, data: ScalaNumericConversions): Option[DataStream] = if (decay > 0)
    Some(DataStream(decay, data, newTimestamp, newTimestamp)) else None

}

/**
 * INTERNAL API
 *
 * @param name the metric name
 *
 * @param value the metric value, which may or may not be defined
 *
 * @param average the data stream of the metric value, for trending over time. Metrics that are already
 *                averages (e.g. system load average) or finite (e.g. as total cores), are not trended.
 */
private[cluster] case class Metric(name: String, value: Option[ScalaNumericConversions], average: Option[DataStream])
  extends ClusterMessage with MetricNumericConverter {

  /**
   * Returns the metric with a new data stream for data trending if eligible,
   * otherwise returns the unchanged metric.
   */
  def initialize(decay: Int): Metric = if (initializable) copy(average = DataStream(decay, value.get)) else this

  /**
   * If defined ( [[akka.cluster.MetricNumericConverter.defined()]] ), updates the new
   * data point, and if defined, updates the data stream. Returns the updated metric.
   */
  def :+(latest: Metric): Metric = latest.value match {
    case Some(v) if this same latest ⇒ average match {
      case Some(previous)                    ⇒ copy(value = Some(v), average = Some(previous :+ v))
      case None if latest.average.isDefined  ⇒ copy(value = Some(v), average = latest.average)
      case None if !latest.average.isDefined ⇒ copy(value = Some(v))
    }
    case None ⇒ this
  }

  /**
   * @see [[akka.cluster.MetricNumericConverter.defined()]]
   */
  def isDefined: Boolean = value match {
    case Some(a) ⇒ defined(a)
    case None    ⇒ false
  }

  /**
   * Returns true if <code>that</code> is tracking the same metric as this.
   */
  def same(that: Metric): Boolean = name == that.name

  /**
   * Returns true if the metric requires initialization.
   */
  def initializable: Boolean = trendable && isDefined && average.isEmpty

  /**
   * Returns true if the metric is a value applicable for trending.
   */
  def trendable: Boolean = !(Metric.noStream contains name)

}

/**
 * INTERNAL API
 *
 * Companion object of Metric class.
 */
private[cluster] object Metric extends MetricNumericConverter {

  /**
   * The metrics that are already averages or finite are not trended over time.
   */
  private val noStream = Set("system-load-average", "total-cores", "processors")

  /**
   * Evaluates validity of <code>value</code> based on whether it is available (SIGAR on classpath)
   * or defined for the OS (JMX). If undefined we set the value option to None and do not modify
   * the latest sampled metric to avoid skewing the statistical trend.
   */
  def apply(name: String, value: Option[ScalaNumericConversions]): Metric = value match {
    case Some(v) if defined(v) ⇒ Metric(name, value, None)
    case _                     ⇒ Metric(name, None, None)
  }

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

}

/**
 * INTERNAL API
 *
 * Encapsulates evaluation of validity of metric values, conversion of an actual metric value to
 * a [[akka.cluster.Metric]] for consumption by subscribed cluster entities.
 */
private[cluster] trait MetricNumericConverter {

  /**
   * A defined value is neither a -1 or NaN/Infinite:
   * <ul><li>JMX system load average and max heap can be 'undefined' for certain OS, in which case a -1 is returned</li>
   * <li>SIGAR combined CPU can occasionally return a NaN or Infinite (known bug)</li></ul>
   */
  def defined(value: ScalaNumericConversions): Boolean =
    convert(value) fold (a ⇒ value.underlying != -1, b ⇒ !(b.isNaN || b.isInfinite))

  /**
   * May involve rounding or truncation.
   */
  def convert(from: ScalaNumericConversions): Either[Long, Double] = from match {
    case n: BigInt     ⇒ Left(n.longValue())
    case n: BigDecimal ⇒ Right(n.doubleValue())
    case n: RichInt    ⇒ Left(n.abs)
    case n: RichLong   ⇒ Left(n.self)
    case n: RichDouble ⇒ Right(n.self)
  }

}

/**
 * INTERNAL API
 *
 * Loads JVM metrics through JMX monitoring beans. If Hyperic SIGAR is on the classpath, this
 * loads wider and more accurate range of metrics in combination with SIGAR's native OS library.
 *
 * FIXME switch to Scala reflection
 *
 * @param sigar the optional org.hyperic.Sigar instance
 *
 * @param address The [[akka.actor.Address]] of the node being sampled
 */
private[cluster] class MetricsCollector private (private val sigar: Option[AnyRef], address: Address) extends MetricNumericConverter {

  private val memoryMBean: MemoryMXBean = ManagementFactory.getMemoryMXBean

  private val osMBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean

  private val LoadAverage: Option[Method] = createMethodFrom(sigar, "getLoadAverage")

  private val CpuList: Option[Method] = createMethodFrom(sigar, "getCpuInfoList").map(m ⇒ m)

  private val NetInterfaces: Option[Method] = createMethodFrom(sigar, "getNetInterfaceList")

  private val Cpu: Option[Method] = createMethodFrom(sigar, "getCpuPerc")

  private val CombinedCpu: Option[Method] = Try(Cpu.get.getReturnType.getMethod("getCombined")).toOption

  /**
   * Samples and collects new data points.
   *
   * @return [[akka.cluster.NodeMetrics]]
   */
  def sample: NodeMetrics = NodeMetrics(address, newTimestamp, Set(cpuCombined, totalCores,
    systemLoadAverage, used, committed, max, processors, networkMaxRx, networkMaxTx))

  /**
   * (SIGAR / JMX) Returns the OS-specific average system load on the CPUs in the system, for the past 1 minute.
   * On some systems the JMX OS system load average may not be available, in which case a negative value is returned.
   * Hyperic SIGAR provides more precise values, thus, if the library is on the classpath, it is the default.
   */
  def systemLoadAverage: Metric = Metric("system-load-average", Some(BigDecimal(Try(
    LoadAverage.get.invoke(sigar.get).asInstanceOf[Array[Double]].toSeq.head) getOrElse osMBean.getSystemLoadAverage)))

  /**
   * (JMX) Returns the number of available processors
   */
  def processors: Metric = Metric("processors", Some(BigInt(osMBean.getAvailableProcessors)))

  /**
   * (JMX) Returns the current sum of heap memory used from all heap memory pools (in bytes).
   */
  def used: Metric = Metric("heap-memory-used", Some(BigInt(memoryMBean.getHeapMemoryUsage.getUsed)))

  /**
   * (JMX) Returns the current sum of heap memory guaranteed to be available to the JVM
   * from all heap memory pools (in bytes). Committed will always be greater
   * than or equal to used.
   */
  def committed: Metric = Metric("heap-memory-committed", Some(BigInt(memoryMBean.getHeapMemoryUsage.getCommitted)))

  /**
   * (JMX) Returns the maximum amount of memory (in bytes) that can be used
   * for JVM memory management. If undefined, returns -1.
   */
  def max: Metric = Metric("heap-memory-max", Some(BigInt(memoryMBean.getHeapMemoryUsage.getMax)))

  /**
   * (SIGAR) Returns the combined CPU sum of User + Sys + Nice + Wait, in percentage. This metric can describe
   * the amount of time the CPU spent executing code during n-interval and how much more it could
   * theoretically. Note that 99% CPU utilization can be optimal or indicative of failure.
   *
   * In the data stream, this will sometimes return with a valid metric value, and sometimes as a NaN or Infinite.
   * Documented bug https://bugzilla.redhat.com/show_bug.cgi?id=749121 and several others.
   */
  def cpuCombined: Metric = Metric("cpu-combined", Try(BigDecimal(CombinedCpu.get.invoke(Cpu.get.invoke(sigar.get)).asInstanceOf[Double])).toOption)

  /**
   * (SIGAR) Returns the total number of cores.
   */
  def totalCores: Metric = Metric("total-cores", Try(BigInt(CpuList.get.invoke(sigar.get).asInstanceOf[Array[AnyRef]].map(cpu ⇒
    createMethodFrom(Some(cpu), "getTotalCores").get.invoke(cpu).asInstanceOf[Int]).head)).toOption)
  //Array[Int].head - if this would differ on some servers, expose all. In testing each int was always equal.

  /**
   * (SIGAR) Returns the max network IO read/write value, in bytes, for network latency evaluation.
   */
  def networkMaxRx: Metric = networkMaxFor("getRxBytes", "network-max-rx")

  /**
   * (SIGAR) Returns the max network IO tx value, in bytes.
   */
  def networkMaxTx: Metric = networkMaxFor("getTxBytes", "network-max-tx")

  /**
   * Returns the network stats per interface.
   */
  def networkStats: Map[String, AnyRef] = Try(NetInterfaces.get.invoke(sigar.get).asInstanceOf[Array[String]].map(arg ⇒
    arg -> (createMethodFrom(sigar, "getNetInterfaceStat", Array(classOf[String])).get.invoke(sigar.get, arg))).toMap) getOrElse Map.empty[String, AnyRef]

  /**
   * Returns true if SIGAR is successfully installed on the classpath, otherwise false.
   */
  def isSigar: Boolean = sigar.isDefined

  /**
   * Releases any native resources associated with this instance.
   */
  def close(): Unit = if (isSigar) Try(createMethodFrom(sigar, "close").get.invoke(sigar.get)) getOrElse Unit

  /**
   * Returns the max bytes for the given <code>method</code> in metric for <code>metric</code> from the network interface stats.
   */
  private def networkMaxFor(method: String, metric: String): Metric = Metric(metric, Try(Some(BigInt(
    networkStats.collect { case (_, a) ⇒ createMethodFrom(Some(a), method).get.invoke(a).asInstanceOf[Long] }.max))) getOrElse None)

  private def createMethodFrom(ref: Option[AnyRef], method: String, types: Array[(Class[_])] = Array.empty[(Class[_])]): Option[Method] =
    Try(ref.get.getClass.getMethod(method, types: _*)).toOption

}

/**
 * INTERNAL API
 * Companion object of MetricsCollector class.
 */
private[cluster] object MetricsCollector {
  def apply(address: Address, log: LoggingAdapter, dynamicAccess: DynamicAccess): MetricsCollector =
    dynamicAccess.createInstanceFor[AnyRef]("org.hyperic.sigar.Sigar", Seq.empty) match {
      case Success(identity) ⇒ new MetricsCollector(Some(identity), address)
      case Failure(e) ⇒
        log.debug(e.toString)
        log.info("Hyperic SIGAR was not found on the classpath or not installed properly. " +
          "Metrics will be retreived from MBeans, and may be incorrect on some platforms. " +
          "To increase metric accuracy add the 'sigar.jar' to the classpath and the appropriate" +
          "platform-specific native libary to 'java.library.path'.")
        new MetricsCollector(None, address)
    }
}

