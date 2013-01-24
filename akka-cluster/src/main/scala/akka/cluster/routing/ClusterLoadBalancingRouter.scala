/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.ClusterMetricsChanged
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.NodeMetrics
import akka.cluster.NodeMetrics.MetricValues
import akka.dispatch.Dispatchers
import akka.event.Logging
import akka.routing.Broadcast
import akka.routing.Destination
import akka.routing.Resizer
import akka.routing.Route
import akka.routing.RouteeProvider
import akka.routing.RouterConfig

/**
 * INTERNAL API
 */
private[cluster] object ClusterLoadBalancingRouter {
  val defaultSupervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }
}

/**
 * A Router that performs load balancing to cluster nodes based on
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
case class ClusterLoadBalancingRouter(
  metricsSelector: MetricsSelector,
  nrOfInstances: Int = 0, routees: Iterable[String] = Nil,
  override val resizer: Option[Resizer] = None,
  val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  val supervisorStrategy: SupervisorStrategy = ClusterLoadBalancingRouter.defaultSupervisorStrategy)
  extends RouterConfig with ClusterLoadBalancingRouterLike {

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
    this(metricsSelector = selector, routees = routeePaths.asScala)

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
  def withDispatcher(dispatcherId: String): ClusterLoadBalancingRouter =
    copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): ClusterLoadBalancingRouter =
    copy(supervisorStrategy = strategy)

}

/**
 * INTERNAL API.
 *
 * This strategy is a metrics-aware router which performs load balancing of
 * cluster nodes based on cluster metric data. It consumes [[akka.cluster.ClusterMetricsChanged]]
 * events and the [[akka.cluster.routing.MetricsSelector]] creates an mix of
 * weighted routees based on the node metrics. Messages are routed randomly to the
 * weighted routees, i.e. nodes with lower load are more likely to be used than nodes with
 * higher load
 */
trait ClusterLoadBalancingRouterLike { this: RouterConfig ⇒

  def metricsSelector: MetricsSelector

  def nrOfInstances: Int

  def routees: Iterable[String]

  def routerDispatcher: String

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    val log = Logging(routeeProvider.context.system, routeeProvider.context.self)

    // Function that points to the routees to use, starts with the plain routees
    // of the routeeProvider and then changes to the current weighted routees
    // produced by the metricsSelector. The reason for using a function is that
    // routeeProvider.routees can change.
    @volatile var weightedRoutees: () ⇒ IndexedSeq[ActorRef] = () ⇒ routeeProvider.routees

    // subscribe to ClusterMetricsChanged and update weightedRoutees
    val metricsListener = routeeProvider.context.actorOf(Props(new Actor {

      val cluster = Cluster(routeeProvider.context.system)

      override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterMetricsChanged])
      override def postStop(): Unit = cluster.unsubscribe(self)

      def receive = {
        case ClusterMetricsChanged(metrics) ⇒ receiveMetrics(metrics)
        case _: CurrentClusterState         ⇒ // ignore
      }

      def receiveMetrics(metrics: Set[NodeMetrics]): Unit = {
        val routees = metricsSelector.weightedRefs(routeeProvider.routees, cluster.selfAddress, metrics)
        weightedRoutees = () ⇒ routees
      }

    }).withDispatcher(routerDispatcher), name = "metricsListener")

    def getNext(): ActorRef = {
      val currentRoutees = weightedRoutees.apply
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
 * Low heap capacity => lower weight.
 */
@SerialVersionUID(1L)
case object HeapMetricsSelector extends MetricsSelector {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  override def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double] = {
    nodeMetrics.map { n ⇒
      val (used, committed, max) = MetricValues.unapply(n.heapMemory)
      val capacity = max match {
        case None    ⇒ (committed - used).toDouble / committed
        case Some(m) ⇒ (m - used).toDouble / m
      }
      (n.address, capacity)
    }.toMap
  }
}

// FIXME implement more MetricsSelectors, such as CpuMetricsSelector,
//       LoadAverageMetricsSelector, NetworkMetricsSelector.
//       Also a CompositeMetricsSelector which uses a mix of other
//       selectors.

/**
 * A MetricsSelector is responsible for producing weighted mix of routees
 * from the node metrics. The weights are typically proportional to the
 * remaining capacity.
 */
abstract class MetricsSelector {

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
      capacity mapValues { c ⇒ math.round((c) / divisor).toInt }
    }
  }

  /**
   * Allocates a list of actor refs according to the weight of their node, i.e.
   * weight 3 of node A will allocate 3 slots for each ref with address A.
   */
  def weightedRefs(refs: IndexedSeq[ActorRef], selfAddress: Address, weights: Map[Address, Int]): IndexedSeq[ActorRef] = {
    def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
      case Address(_, _, None, None) ⇒ selfAddress
      case a                         ⇒ a
    }

    val w = weights.withDefaultValue(1)
    refs.foldLeft(IndexedSeq.empty[ActorRef]) { (acc, ref) ⇒
      acc ++ IndexedSeq.fill(w(fullAddress(ref)))(ref)
    }
  }

  /**
   * Combines the different pieces to allocate a list of weighted actor refs
   * based on the node metrics.
   */
  def weightedRefs(refs: IndexedSeq[ActorRef], selfAddress: Address, nodeMetrics: Set[NodeMetrics]): IndexedSeq[ActorRef] =
    weightedRefs(refs, selfAddress, weights(capacity(nodeMetrics)))
}