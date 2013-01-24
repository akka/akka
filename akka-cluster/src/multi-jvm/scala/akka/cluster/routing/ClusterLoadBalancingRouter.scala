/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import language.implicitConversions
import language.postfixOps
import akka.actor._
import akka.cluster._
import akka.routing._
import akka.dispatch.Dispatchers
import akka.event.Logging
import java.util.concurrent.atomic.{ AtomicLong, AtomicReference }
import akka.routing.Destination
import akka.cluster.NodeMetrics
import akka.routing.Broadcast
import akka.actor.OneForOneStrategy
import akka.cluster.ClusterEvent.ClusterMetricsChanged
import akka.cluster.ClusterEvent.CurrentClusterState
import util.Try
import akka.cluster.NodeMetrics.{ NodeMetricsComparator, MetricValues }
import NodeMetricsComparator._

/**
 * INTERNAL API.
 *
 * Trait that embodies the contract for all load balancing implementations.
 */
private[cluster] trait LoadBalancer {

  /**
   * Compares only those nodes that are deemed 'available' by the
   * [[akka.routing.RouteeProvider]]
   */
  def selectNodeByHealth(availableNodes: Set[NodeMetrics]): Option[Address]

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterLoadBalancingRouter {
  val defaultSupervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }
}

/**
 * INTERNAL API.
 *
 * The abstract consumer of [[akka.cluster.ClusterMetricsChanged]] events and the primary consumer
 * of cluster metric data. This strategy is a metrics-aware router which performs load balancing of
 * cluster nodes with a fallback strategy of a [[akka.routing.RoundRobinRouter]].
 *
 * Load balancing of nodes is based on .. etc etc desc forthcoming
 */
trait ClusterAdaptiveLoadBalancingRouterLike extends RoundRobinLike with LoadBalancer { this: RouterConfig ⇒

  def routerDispatcher: String

  override def createRoute(routeeProvider: RouteeProvider): Route = {
    if (resizer.isEmpty) {
      if (routees.isEmpty) routeeProvider.createRoutees(nrOfInstances)
      else routeeProvider.registerRouteesFor(routees)
    }

    val log = Logging(routeeProvider.context.system, routeeProvider.context.self)

    val next = new AtomicLong(0)

    val nodeMetrics = new AtomicReference[Set[NodeMetrics]](Set.empty)

    val metricsListener = routeeProvider.context.actorOf(Props(new Actor {
      def receive = {
        case ClusterMetricsChanged(metrics) ⇒ receiveMetrics(metrics)
        case _: CurrentClusterState         ⇒ // ignore
      }
      def receiveMetrics(metrics: Set[NodeMetrics]): Unit = {
        val availableNodes = routeeProvider.routees.map(_.path.address).toSet
        val updated: Set[NodeMetrics] = nodeMetrics.get.collect { case node if availableNodes contains node.address ⇒ node }
        nodeMetrics.set(updated)
      }
      override def postStop(): Unit = Cluster(routeeProvider.context.system) unsubscribe self
    }).withDispatcher(routerDispatcher), name = "metricsListener")
    Cluster(routeeProvider.context.system).subscribe(metricsListener, classOf[ClusterMetricsChanged])

    def getNext(): ActorRef = {
      // TODO use as/where you will... selects by health category based on the implementation
      val address: Option[Address] = selectNodeByHealth(nodeMetrics.get)
      // TODO actual routee selection. defaults to round robin.
      routeeProvider.routees((next.getAndIncrement % routees.size).asInstanceOf[Int])
    }

    def routeTo(): ActorRef = if (routeeProvider.routees.isEmpty) routeeProvider.context.system.deadLetters else getNext()

    {
      case (sender, message) ⇒
        message match {
          case Broadcast(msg) ⇒ toAll(sender, routeeProvider.routees)
          case msg            ⇒ List(Destination(sender, routeTo()))
        }
    }
  }
}

/**
 * Selects by all monitored metric types (memory, network latency, cpu...) and
 * chooses the healthiest node to route to.
 */
@SerialVersionUID(1L)
private[cluster] case class ClusterAdaptiveMetricsLoadBalancingRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil,
                                                                      override val resizer: Option[Resizer] = None,
                                                                      val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                                                      val supervisorStrategy: SupervisorStrategy = ClusterLoadBalancingRouter.defaultSupervisorStrategy)
  extends RouterConfig with ClusterAdaptiveLoadBalancingRouterLike with MetricsAwareClusterNodeSelector {

  // TODO
  def selectNodeByHealth(nodes: Set[NodeMetrics]): Option[Address] = {
    val s = Set(selectByMemory(nodes), selectByNetworkLatency(nodes), selectByCpu(nodes))
    s.head // TODO select the Address that appears with the highest or lowest frequency
  }
}

@SerialVersionUID(1L)
private[cluster] case class MemoryLoadBalancingRouter(nrOfInstances: Int = 0, routees: Iterable[String] = Nil,
                                                                     override val resizer: Option[Resizer] = None,
                                                                     val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                                                     val supervisorStrategy: SupervisorStrategy = ClusterLoadBalancingRouter.defaultSupervisorStrategy)
  extends RouterConfig with ClusterAdaptiveLoadBalancingRouterLike with MetricsAwareClusterNodeSelector {

  def selectNodeByHealth(nodes: Set[NodeMetrics]): Option[Address] = selectByMemory(nodes)
}

@SerialVersionUID(1L)
private[cluster] case class CpuLoadBalancer(nrOfInstances: Int = 0, routees: Iterable[String] = Nil,
                                            override val resizer: Option[Resizer] = None,
                                            val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                            val supervisorStrategy: SupervisorStrategy = ClusterLoadBalancingRouter.defaultSupervisorStrategy)
  extends RouterConfig with ClusterAdaptiveLoadBalancingRouterLike with MetricsAwareClusterNodeSelector {

  // TODO
  def selectNodeByHealth(nodes: Set[NodeMetrics]): Option[Address] = None
}

@SerialVersionUID(1L)
private[cluster] case class NetworkLatencyLoadBalancer(nrOfInstances: Int = 0, routees: Iterable[String] = Nil,
                                                       override val resizer: Option[Resizer] = None,
                                                       val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
                                                       val supervisorStrategy: SupervisorStrategy = ClusterLoadBalancingRouter.defaultSupervisorStrategy)
  extends RouterConfig with ClusterAdaptiveLoadBalancingRouterLike with MetricsAwareClusterNodeSelector {

  // TODO
  def selectNodeByHealth(nodes: Set[NodeMetrics]): Option[Address] = None
}
