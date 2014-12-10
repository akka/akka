/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

// TODO move this unit to akka-cluster-toolbox/.../akka.cluster.toolbox.metrics.ClusterMetrics.scala

package akka.cluster

import akka.actor._
import java.io.Closeable
import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }
import java.lang.management.{ OperatingSystemMXBean, MemoryMXBean, ManagementFactory }
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.{ Try, Success, Failure }
import akka.ConfigurationException
import akka.cluster.MemberStatus.Up
import akka.event.Logging
import java.lang.management.MemoryUsage
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import akka.event.LoggingAdapter
import akka.util.Helpers.Requiring
import akka.util.Helpers.ConfigOps
import akka.dispatch.Dispatchers

/**
 * Metrics extension settings. Documented in: `src/main/resources/reference.conf`.
 */
case class ClusterMetricsSettings(val config: Config) {
  private val cc = config.getConfig("akka.cluster-toolbox.metrics")
  // Extension.
  val MetricsDispatcher: String = cc.getString("dispatcher") match {
    case "" ⇒ Dispatchers.DefaultDispatcherId
    case id ⇒ id
  }
  val PeriodicTasksInitialDelay: FiniteDuration = cc.getMillisDuration("periodic-tasks-initial-delay")
  //  Supervisor.
  val SupervisorName: String = cc.getString("supervisor.name")
  val SupervisorStrategyProvider: String = cc.getString("supervisor.strategy.provider")
  val SupervisorStrategyConfiguration: Config = cc.getConfig("supervisor.strategy.configuration")
  // Collector.
  val CollectorName: String = cc.getString("collector.name")
  val CollectorEnabled: Boolean = cc.getBoolean("collector.enabled")
  val CollectorProvider: String = cc.getString("collector.provider")
  val CollectorSampleInterval: FiniteDuration = {
    cc.getMillisDuration("collector.sample-interval")
  } requiring (_ > Duration.Zero, "collector.sample-interval must be > 0")
  val CollectorGossipInterval: FiniteDuration = {
    cc.getMillisDuration("collector.gossip-interval")
  } requiring (_ > Duration.Zero, "collector.gossip-interval must be > 0")
  val CollectorMovingAverageHalfLife: FiniteDuration = {
    cc.getMillisDuration("collector.moving-average-half-life")
  } requiring (_ > Duration.Zero, "collector.moving-average-half-life must be > 0")
}

/**
 * Cluster metrics extension.
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
class ClusterMetricsExtension(system: ExtendedActorSystem) extends Extension {

  private val log: LoggingAdapter = Logging(system, getClass.getName)

  val settings = ClusterMetricsSettings(system.settings.config)
  import settings._

  val strategy = system.dynamicAccess.createInstanceFor[SupervisorStrategy](
    SupervisorStrategyProvider, immutable.Seq(classOf[Config] -> SupervisorStrategyConfiguration))
    .getOrElse {
      log.error(s"Configured strategy provider ${SupervisorStrategyProvider} failed to load, using default ${classOf[ClusterMetricsStrategy].getName}.")
      new ClusterMetricsStrategy(SupervisorStrategyConfiguration)
    }

  val supervisor = system.systemActorOf(
    Props(classOf[ClusterMetricsSupervisor]).withDispatcher(MetricsDispatcher).withDeploy(Deploy.local),
    SupervisorName)

}

/**
 * Cluster metrics extension provider.
 */
object ClusterMetricsExtension extends ExtensionId[ClusterMetricsExtension] with ExtensionIdProvider {
  override def lookup = ClusterMetricsExtension
  override def get(system: ActorSystem): ClusterMetricsExtension = super.get(system)
  override def createExtension(system: ExtendedActorSystem): ClusterMetricsExtension = new ClusterMetricsExtension(system)
  /** Runtime collection management. */
  trait CollectionControlMessage extends Serializable
  case object CollectionStartMessage extends CollectionControlMessage
  case object CollectionStopMessage extends CollectionControlMessage
}

/**
 * Default [[ClusterMetricsSupervisor]] strategy:
 * A configurable [[OneForOneStrategy]] with restart-on-throwable decider.
 */
class ClusterMetricsStrategy(config: Config) extends OneForOneStrategy(
  maxNrOfRetries = config.getInt("maxNrOfRetries"),
  withinTimeRange = config.getMillisDuration("withinTimeRange"),
  loggingEnabled = config.getBoolean("loggingEnabled"))(ClusterMetricsStrategy.metricsDecider)

/**
 * Provide custom metrics strategy resources.
 */
object ClusterMetricsStrategy {
  import akka.actor.SupervisorStrategy._
  /**
   * [[SupervisorStrategy.Decider]] which allows to survive intermittent Sigar native method calls failures.
   */
  val metricsDecider: SupervisorStrategy.Decider = {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Throwable                    ⇒ Restart
  }
}

/**
 * INTERNAL API.
 *
 * Actor providing customizable collector supervision.
 */
private[cluster] class ClusterMetricsSupervisor extends Actor with ActorLogging {
  import ClusterMetricsExtension._
  val metrics = ClusterMetricsExtension(context.system)
  import metrics.settings._

  override val supervisorStrategy = metrics.strategy

  override def preStart() = {
    if (CollectorEnabled) {
      self ! CollectionStartMessage
    } else {
      log.warning(s"Metrics collection is disabled in configuration. Use CollectionControlMessage to enable collection at runtime.")
    }
  }

  def childrenCreate() = {
    childrenDelete()
    context.actorOf(Props(classOf[ClusterMetricsCollector]), CollectorName)
  }

  def childrenDelete() = {
    context.children foreach { child ⇒
      context.unwatch(child)
      context.stop(child)
    }
  }

  override def receive = {
    case CollectionStartMessage ⇒
      log.info(s"Collection started.")
      childrenCreate
    case CollectionStopMessage ⇒
      log.info(s"Collection stopped.")
      childrenDelete
  }

}

/**
 * INTERNAL API.
 *
 * Actor responsible for periodic data sampling in the node and publication to the cluster.
 */
private[cluster] class ClusterMetricsCollector extends Actor with ActorLogging {

  import InternalClusterAction._
  import ClusterEvent._
  import Member.addressOrdering
  import context.dispatcher
  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, scheduler, settings }
  import cluster.InfoLogger._
  val metrics = ClusterMetricsExtension(context.system)
  import metrics.settings._

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
  val collector: MetricsCollector = MetricsCollector(context.system)

  /**
   * Start periodic gossip to random nodes in cluster
   */
  val gossipTask = scheduler.schedule(PeriodicTasksInitialDelay max CollectorGossipInterval,
    CollectorGossipInterval, self, GossipTick)

  /**
   * Start periodic metrics collection
   */
  val sampleTask = scheduler.schedule(PeriodicTasksInitialDelay max CollectorSampleInterval,
    CollectorSampleInterval, self, MetricsTick)

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
    case MemberRemoved(m, _)        ⇒ removeMember(m)
    case MemberExited(m)            ⇒ removeMember(m)
    case UnreachableMember(m)       ⇒ removeMember(m)
    case ReachableMember(m)         ⇒ if (m.status == Up) addMember(m)
    case _: MemberEvent             ⇒ // not interested in other types of MemberEvent

  }

  override def postStop: Unit = {
    cluster unsubscribe self
    gossipTask.cancel()
    sampleTask.cancel()
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
   * Updates the initial node ring for those nodes that are [[akka.cluster.MemberStatus.Up]].
   */
  def receiveState(state: CurrentClusterState): Unit =
    nodes = state.members collect { case m if m.status == Up ⇒ m.address }

  /**
   * Samples the latest metrics for the node, updates metrics statistics in
   * [[akka.cluster.MetricsGossip]], and publishes the change to the event bus.
   *
   * @see [[akka.cluster.ClusterMetricsCollector.collect( )]]
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
  def publish(): Unit = context.system.eventStream publish ClusterMetricsChanged(latestGossip.nodes)

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
   * Removes nodes if their correlating node ring members are not [[akka.cluster.MemberStatus.Up]]
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

object EWMA {
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
final case class EWMA(value: Double, alpha: Double) {

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
object Metric extends MetricNumericConverter {

  /**
   * Creates a new Metric instance if the value is valid, otherwise None
   * is returned. Invalid numeric values are negative and NaN/Infinite.
   */
  def create(name: String, value: Number, decayFactor: Option[Double]): Option[Metric] =
    if (defined(value)) Some(new Metric(name, value, createEWMA(value.doubleValue, decayFactor)))
    else None

  /**
   * Creates a new Metric instance if the Try is successful and the value is valid,
   * otherwise None is returned. Invalid numeric values are negative and NaN/Infinite.
   */
  def create(name: String, value: Try[Number], decayFactor: Option[Double]): Option[Metric] = value match {
    case Success(v) ⇒ create(name, v, decayFactor)
    case Failure(_) ⇒ None
  }

  def createEWMA(value: Double, decayFactor: Option[Double]): Option[EWMA] = decayFactor match {
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
 * @param metrics the set of sampled [[akka.actor.Metric]]
 */
@SerialVersionUID(1L)
final case class NodeMetrics(address: Address, timestamp: Long, metrics: Set[Metric] = Set.empty[Metric]) {

  /**
   * Returns the most recent data.
   */
  def merge(that: NodeMetrics): NodeMetrics = {
    require(address == that.address, s"merge only allowed for same address, [$address] != [$that.address]")
    if (timestamp >= that.timestamp) this // that is older
    else {
      // equality is based on the name of the Metric and Set doesn't replace existing element
      copy(metrics = that.metrics ++ metrics, timestamp = that.timestamp)
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
object StandardMetrics {

  // Constants for the heap related Metric names
  final val HeapMemoryUsed = "heap-memory-used"
  final val HeapMemoryCommitted = "heap-memory-committed"
  final val HeapMemoryMax = "heap-memory-max"

  // Constants for the cpu related Metric names
  final val SystemLoadAverage = "system-load-average"
  final val Processors = "processors"
  final val CpuCombined = "cpu-combined"
  final val CpuStolen = "cpu-stolen"

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
class JmxMetricsCollector(address: Address, decayFactor: Double) extends MetricsCollector {
  import StandardMetrics._

  private def this(address: Address, settings: ClusterMetricsSettings) =
    this(address,
      EWMA.alpha(settings.CollectorMovingAverageHalfLife, settings.CollectorSampleInterval))

  /**
   * This constructor is used when creating an instance from configured FQCN
   */
  def this(system: ActorSystem) = this(Cluster(system).selfAddress, ClusterMetricsExtension(system).settings)

  private val decayFactorOption = Some(decayFactor)

  private val memoryMBean: MemoryMXBean = ManagementFactory.getMemoryMXBean

  private val osMBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean

  /**
   * Samples and collects new data points.
   * Creates a new instance each time.
   */
  def sample(): NodeMetrics = NodeMetrics(address, newTimestamp, metrics)

  /**
   * Generate metrics set.
   * Creates a new instance each time.
   */
  def metrics(): Set[Metric] = {
    val heap = heapMemoryUsage
    Set(systemLoadAverage, heapUsed(heap), heapCommitted(heap), heapMax(heap), processors).flatten
  }

  /**
   * (JMX) Returns the OS-specific average load on the CPUs in the system, for the past 1 minute.
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
import org.hyperic.sigar.Sigar
class SigarMetricsCollector(address: Address, decayFactor: Double, sigar: Sigar)
  extends JmxMetricsCollector(address, decayFactor) {

  import StandardMetrics._
  import org.hyperic.sigar.CpuPerc

  private def this(address: Address, settings: ClusterMetricsSettings) =
    this(address,
      EWMA.alpha(settings.CollectorMovingAverageHalfLife, settings.CollectorSampleInterval),
      new Sigar())

  /**
   * This constructor is used when creating an instance from configured FQCN
   */
  def this(system: ActorSystem) = this(Cluster(system).selfAddress, ClusterMetricsExtension(system).settings)

  private val decayFactorOption = Some(decayFactor)

  /**
   * Verify at the end of construction that Sigar is operational.
   */
  metrics()

  // Construction complete.

  override def metrics(): Set[Metric] = {
    // Must obtain cpuPerc in one shot. https://github.com/akka/akka/issues/16121
    val cpuPerc = sigar.getCpuPerc
    super.metrics ++ Set(cpuCombined(cpuPerc), cpuStolen(cpuPerc)).flatten
  }

  /**
   * (SIGAR) Returns the OS-specific average load on the CPUs in the system, for the past 1 minute.
   *
   * Creates a new instance each time.
   */
  override def systemLoadAverage: Option[Metric] = Metric.create(
    name = SystemLoadAverage,
    value = sigar.getLoadAverage()(0).asInstanceOf[Number],
    decayFactor = None)

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
  def cpuCombined(cpuPerc: CpuPerc): Option[Metric] = Metric.create(
    name = CpuCombined,
    value = cpuPerc.getCombined.asInstanceOf[Number],
    decayFactor = decayFactorOption)

  /**
   * (SIGAR) Returns the stolen CPU time. Relevant to virtual hosting environments.
   * For details please see: [[http://en.wikipedia.org/wiki/CPU_time#Subdivision Wikipedia - CPU time subdivision]] and
   * [[https://www.datadoghq.com/2013/08/understanding-aws-stolen-cpu-and-how-it-affects-your-apps/ Understanding AWS stolen CPU and how it affects your apps]]
   *
   * Creates a new instance each time.
   */
  def cpuStolen(cpuPerc: CpuPerc): Option[Metric] = Metric.create(
    name = CpuStolen,
    value = cpuPerc.getStolen.asInstanceOf[Number],
    decayFactor = decayFactorOption)

  /**
   * Releases any native resources associated with this instance.
   */
  override def close: Unit = sigar.close

}

/**
 * INTERNAL API
 * Factory to create configured MetricsCollector.
 * If instantiation of SigarMetricsCollector fails (missing class or native library)
 * it falls back to use JmxMetricsCollector.
 */
private[cluster] object MetricsCollector {
  def apply(_system: ActorSystem): MetricsCollector = {
    val system = _system.asInstanceOf[ExtendedActorSystem]
    val settings = ClusterMetricsSettings(system.settings.config)
    import settings.{ CollectorProvider ⇒ fqcn }
    def log = Logging(system, getClass.getName)
    if (fqcn == classOf[SigarMetricsCollector].getName) {
      // Not using [[Try]] since any type of Sigar load failure is non-fatal in this case. 
      try {
        new SigarMetricsCollector(system)
      } catch {
        case e: Throwable ⇒
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
