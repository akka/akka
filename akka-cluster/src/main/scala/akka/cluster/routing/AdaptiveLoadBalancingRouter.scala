/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import java.util.Arrays

import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.immutable
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.dispatch.Dispatchers
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.ClusterMetricsChanged
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.NodeMetrics
import akka.cluster.StandardMetrics.Cpu
import akka.cluster.StandardMetrics.HeapMemory
import akka.event.Logging
import akka.japi.Util.immutableSeq
import akka.routing.Broadcast
import akka.routing.Destination
import akka.routing.FromConfig
import akka.routing.NoRouter
import akka.routing.Resizer
import akka.routing.Route
import akka.routing.RouteeProvider
import akka.routing.RouterConfig

object AdaptiveLoadBalancingRouter {
  private val escalateStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }
}

/**
 * A Router that performs load balancing of messages to cluster nodes based on
 * cluster metric data.
 *
 * It uses random selection of routees based probabilities derived from
 * the remaining capacity of corresponding node.
 *
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical
 * sense as this means that the router should both create new actors and use the 'routees'
 * actor(s). In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * <h1>Supervision Setup</h1>
 *
 * The router creates a “head” actor which supervises and/or monitors the
 * routees. Instances are created as children of this actor, hence the
 * children are not supervised by the parent of the router. Common choices are
 * to always escalate (meaning that fault handling is always applied to all
 * children simultaneously; this is the default) or use the parent’s strategy,
 * which will result in routed children being treated individually, but it is
 * possible as well to use Routers to give different supervisor strategies to
 * different groups of children.
 *
 * @param metricsSelector decides what probability to use for selecting a routee, based
 *   on remaining capacity as indicated by the node metrics
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 */
@SerialVersionUID(1L)
case class AdaptiveLoadBalancingRouter(
  metricsSelector: MetricsSelector = MixMetricsSelector,
  nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil,
  override val resizer: Option[Resizer] = None,
  val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  val supervisorStrategy: SupervisorStrategy = AdaptiveLoadBalancingRouter.escalateStrategy)
  extends RouterConfig with AdaptiveLoadBalancingRouterLike {

  /**
   * Constructor that sets nrOfInstances to be created.
   * Java API
   * @param selector the selector is responsible for producing weighted mix of routees from the node metrics
   * @param nr number of routees to create
   */
  def this(selector: MetricsSelector, nr: Int) = this(metricsSelector = selector, nrOfInstances = nr)

  /**
   * Constructor that sets the routees to be used.
   * Java API
   * @param selector the selector is responsible for producing weighted mix of routees from the node metrics
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(selector: MetricsSelector, routeePaths: java.lang.Iterable[String]) =
    this(metricsSelector = selector, routees = immutableSeq(routeePaths))

  /**
   * Constructor that sets the resizer to be used.
   * Java API
   * @param selector the selector is responsible for producing weighted mix of routees from the node metrics
   */
  def this(selector: MetricsSelector, resizer: Resizer) =
    this(metricsSelector = selector, resizer = Some(resizer))

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): AdaptiveLoadBalancingRouter =
    copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): AdaptiveLoadBalancingRouter =
    copy(supervisorStrategy = strategy)

  /**
   * Uses the resizer of the given RouterConfig if this RouterConfig
   * doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   */
  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case _: FromConfig | _: NoRouter ⇒ this
    case otherRouter: AdaptiveLoadBalancingRouter ⇒
      val useResizer =
        if (this.resizer.isEmpty && otherRouter.resizer.isDefined) otherRouter.resizer
        else this.resizer
      copy(resizer = useResizer)
    case _ ⇒ throw new IllegalArgumentException("Expected AdaptiveLoadBalancingRouter, got [%s]".format(other))
  }

}

/**
 * INTERNAL API.
 *
 * This strategy is a metrics-aware router which performs load balancing of messages to
 * cluster nodes based on cluster metric data. It consumes [[akka.cluster.ClusterMetricsChanged]]
 * events and the [[akka.cluster.routing.MetricsSelector]] creates an mix of
 * weighted routees based on the node metrics. Messages are routed randomly to the
 * weighted routees, i.e. nodes with lower load are more likely to be used than nodes with
 * higher load
 */
trait AdaptiveLoadBalancingRouterLike { this: RouterConfig ⇒

  def metricsSelector: MetricsSelector

  def nrOfInstances: Int

  def routees: immutable.Iterable[String]

  def routerDispatcher: String

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    val log = Logging(routeeProvider.context.system, routeeProvider.context.self)

    // The current weighted routees, if any. Weights are produced by the metricsSelector
    // via the metricsListener Actor. It's only updated by the actor, but accessed from
    // the threads of the senders.
    @volatile var weightedRoutees: Option[WeightedRoutees] = None

    // subscribe to ClusterMetricsChanged and update weightedRoutees
    val metricsListener = routeeProvider.context.actorOf(Props(new Actor {

      val cluster = Cluster(context.system)

      override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterMetricsChanged])
      override def postStop(): Unit = cluster.unsubscribe(self)

      def receive = {
        case ClusterMetricsChanged(metrics) ⇒ receiveMetrics(metrics)
        case _: CurrentClusterState         ⇒ // ignore
      }

      def receiveMetrics(metrics: Set[NodeMetrics]): Unit = {
        // this is the only place from where weightedRoutees is updated
        weightedRoutees = Some(new WeightedRoutees(routeeProvider.routees, cluster.selfAddress,
          metricsSelector.weights(metrics)))
      }

    }).withDispatcher(routerDispatcher), name = "metricsListener")

    def getNext(): ActorRef = weightedRoutees match {
      case Some(weighted) ⇒
        if (weighted.isEmpty) routeeProvider.context.system.deadLetters
        else weighted(ThreadLocalRandom.current.nextInt(weighted.total) + 1)
      case None ⇒
        val currentRoutees = routeeProvider.routees
        if (currentRoutees.isEmpty) routeeProvider.context.system.deadLetters
        else currentRoutees(ThreadLocalRandom.current.nextInt(currentRoutees.size))
    }

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routeeProvider.routees)
          case msg            ⇒ List(Destination(sender, getNext()))
        }
    }
  }
}

/**
 * MetricsSelector that uses the heap metrics.
 * Low heap capacity => small weight.
 */
@SerialVersionUID(1L)
case object HeapMetricsSelector extends CapacityMetricsSelector {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  override def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double] = {
    nodeMetrics.collect {
      case HeapMemory(address, _, used, committed, max) ⇒
        val capacity = max match {
          case None    ⇒ (committed - used).toDouble / committed
          case Some(m) ⇒ (m - used).toDouble / m
        }
        (address, capacity)
    }.toMap
  }
}

/**
 * MetricsSelector that uses the combined CPU metrics.
 * Combined CPU is sum of User + Sys + Nice + Wait, in percentage.
 * Low cpu capacity => small weight.
 */
@SerialVersionUID(1L)
case object CpuMetricsSelector extends CapacityMetricsSelector {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  override def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double] = {
    nodeMetrics.collect {
      case Cpu(address, _, _, Some(cpuCombined), _) ⇒
        val capacity = 1.0 - cpuCombined
        (address, capacity)
    }.toMap
  }
}

/**
 * MetricsSelector that uses the system load average metrics.
 * System load average is OS-specific average load on the CPUs in the system,
 * for the past 1 minute. The system is possibly nearing a bottleneck if the
 * system load average is nearing number of cpus/cores.
 * Low load average capacity => small weight.
 */
@SerialVersionUID(1L)
case object SystemLoadAverageMetricsSelector extends CapacityMetricsSelector {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  override def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double] = {
    nodeMetrics.collect {
      case Cpu(address, _, Some(systemLoadAverage), _, processors) ⇒
        val capacity = 1.0 - math.min(1.0, systemLoadAverage / processors)
        (address, capacity)
    }.toMap
  }
}

/**
 * Singleton instance of the default MixMetricsSelector, which uses [akka.cluster.routing.HeapMetricsSelector],
 * [akka.cluster.routing.CpuMetricsSelector], and [akka.cluster.routing.SystemLoadAverageMetricsSelector]
 */
@SerialVersionUID(1L)
object MixMetricsSelector extends MixMetricsSelectorBase(
  Vector(HeapMetricsSelector, CpuMetricsSelector, SystemLoadAverageMetricsSelector)) {

  /**
   * Java API: get the default singleton instance
   */
  def getInstance = this
}

/**
 * MetricsSelector that combines other selectors and aggregates their capacity
 * values. By default it uses [akka.cluster.routing.HeapMetricsSelector],
 * [akka.cluster.routing.CpuMetricsSelector], and [akka.cluster.routing.SystemLoadAverageMetricsSelector]
 */
@SerialVersionUID(1L)
case class MixMetricsSelector(
  selectors: immutable.IndexedSeq[CapacityMetricsSelector])
  extends MixMetricsSelectorBase(selectors)

/**
 * Base class for MetricsSelector that combines other selectors and aggregates their capacity.
 */
@SerialVersionUID(1L)
abstract class MixMetricsSelectorBase(selectors: immutable.IndexedSeq[CapacityMetricsSelector])
  extends CapacityMetricsSelector {

  /**
   * Java API
   */
  def this(selectors: java.lang.Iterable[CapacityMetricsSelector]) = this(immutableSeq(selectors).toVector)

  override def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double] = {
    val combined: immutable.IndexedSeq[(Address, Double)] = selectors.flatMap(_.capacity(nodeMetrics).toSeq)
    // aggregated average of the capacities by address
    combined.foldLeft(Map.empty[Address, (Double, Int)].withDefaultValue((0.0, 0))) {
      case (acc, (address, capacity)) ⇒
        val (sum, count) = acc(address)
        acc + (address -> (sum + capacity, count + 1))
    }.map {
      case (addr, (sum, count)) ⇒ (addr -> sum / count)
    }
  }

}

/**
 * A MetricsSelector is responsible for producing weights from the node metrics.
 */
@SerialVersionUID(1L)
trait MetricsSelector extends Serializable {
  /**
   * The weights per address, based on the the nodeMetrics.
   */
  def weights(nodeMetrics: Set[NodeMetrics]): Map[Address, Int]
}

/**
 * A MetricsSelector producing weights from remaining capacity.
 * The weights are typically proportional to the remaining capacity.
 */
abstract class CapacityMetricsSelector extends MetricsSelector {

  /**
   * Remaining capacity for each node. The value is between
   * 0.0 and 1.0, where 0.0 means no remaining capacity (full
   * utilization) and 1.0 means full remaining capacity (zero
   * utilization).
   */
  def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double]

  /**
   * Converts the capacity values to weights. The node with lowest
   * capacity gets weight 1 (lowest usable capacity is 1%) and other
   * nodes gets weights proportional to their capacity compared to
   * the node with lowest capacity.
   */
  def weights(capacity: Map[Address, Double]): Map[Address, Int] = {
    if (capacity.isEmpty) Map.empty[Address, Int]
    else {
      val (_, min) = capacity.minBy { case (_, c) ⇒ c }
      // lowest usable capacity is 1% (>= 0.5% will be rounded to weight 1), also avoids div by zero
      val divisor = math.max(0.01, min)
      capacity map { case (addr, c) ⇒ (addr -> math.round((c) / divisor).toInt) }
    }
  }

  /**
   * The weights per address, based on the capacity produced by
   * the nodeMetrics.
   */
  override def weights(nodeMetrics: Set[NodeMetrics]): Map[Address, Int] =
    weights(capacity(nodeMetrics))

}

/**
 * INTERNAL API
 *
 * Pick routee based on its weight. Higher weight, higher probability.
 */
private[cluster] class WeightedRoutees(refs: immutable.IndexedSeq[ActorRef], selfAddress: Address, weights: Map[Address, Int]) {

  // fill an array of same size as the refs with accumulated weights,
  // binarySearch is used to pick the right bucket from a requested value
  // from 1 to the total sum of the used weights.
  private val buckets: Array[Int] = {
    def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
      case Address(_, _, None, None) ⇒ selfAddress
      case a                         ⇒ a
    }
    val buckets = Array.ofDim[Int](refs.size)
    val meanWeight = if (weights.isEmpty) 1 else weights.values.sum / weights.size
    val w = weights.withDefaultValue(meanWeight) // we don’t necessarily have metrics for all addresses
    var i = 0
    var sum = 0
    refs foreach { ref ⇒
      sum += w(fullAddress(ref))
      buckets(i) = sum
      i += 1
    }
    buckets
  }

  def isEmpty: Boolean = buckets.length == 0

  def total: Int = {
    require(!isEmpty, "WeightedRoutees must not be used when empty")
    buckets(buckets.length - 1)
  }

  /**
   * Pick the routee matching a value, from 1 to total.
   */
  def apply(value: Int): ActorRef = {
    require(1 <= value && value <= total, "value must be between [1 - %s]" format total)
    refs(idx(Arrays.binarySearch(buckets, value)))
  }

  /**
   * Converts the result of Arrays.binarySearch into a index in the buckets array
   * see documentation of Arrays.binarySearch for what it returns
   */
  private def idx(i: Int): Int = {
    if (i >= 0) i // exact match
    else {
      val j = math.abs(i + 1)
      if (j >= buckets.length) throw new IndexOutOfBoundsException(
        "Requested index [%s] is > max index [%s]".format(i, buckets.length))
      else j
    }
  }
}