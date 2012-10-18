/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import akka.remote.testkit.{MultiNodeSpec, MultiNodeConfig}
import akka.testkit.{LongRunningTest, DefaultTimeout, ImplicitSender}
import akka.actor._
import akka.cluster.{ MemberStatus, MultiNodeClusterSpec }
import akka.cluster.routing.ClusterRoundRobinRoutedActorMultiJvmSpec.SomeActor


object ClusterAdaptiveLoadBalancingRouterMultiJvmSpec extends MultiNodeConfig {

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  // TODO - config
  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterAdaptiveLoadBalancingRouterMultiJvmNode1 extends ClusterAdaptiveLoadBalancingRouterSpec
class ClusterAdaptiveLoadBalancingRouterMultiJvmNode2 extends ClusterAdaptiveLoadBalancingRouterSpec
class ClusterAdaptiveLoadBalancingRouterMultiJvmNode3 extends ClusterAdaptiveLoadBalancingRouterSpec
class ClusterAdaptiveLoadBalancingRouterMultiJvmNode4 extends ClusterAdaptiveLoadBalancingRouterSpec
class ClusterAdaptiveLoadBalancingRouterMultiJvmNode5 extends ClusterAdaptiveLoadBalancingRouterSpec

abstract class ClusterAdaptiveLoadBalancingRouterSpec extends MultiNodeSpec(ClusterAdaptiveLoadBalancingRouterMultiJvmSpec)
with MultiNodeClusterSpec
with ImplicitSender with DefaultTimeout {
  import ClusterAdaptiveLoadBalancingRouterMultiJvmSpec._

  // TODO configure properly and leverage the other pending load balancing routers
  lazy val router1 = system.actorOf(Props[SomeActor].withRouter(ClusterRouterConfig(MemoryLoadBalancingRouter(),
    ClusterRouterSettings(totalInstances = 3, maxInstancesPerNode = 1))), "router1")
  lazy val router2 = system.actorOf(Props[SomeActor].withRouter(ClusterRouterConfig(MemoryLoadBalancingRouter(),
    ClusterRouterSettings(totalInstances = 3, maxInstancesPerNode = 1))), "router2")
  lazy val router3 = system.actorOf(Props[SomeActor].withRouter(ClusterRouterConfig(MemoryLoadBalancingRouter(),
    ClusterRouterSettings(totalInstances = 3, maxInstancesPerNode = 1))), "router3")
  lazy val router4 = system.actorOf(Props[SomeActor].withRouter(ClusterRouterConfig(MemoryLoadBalancingRouter(),
    ClusterRouterSettings(totalInstances = 3, maxInstancesPerNode = 1))), "router4")
  lazy val router5 = system.actorOf(Props[SomeActor].withRouter(ClusterRouterConfig(MemoryLoadBalancingRouter(),
    ClusterRouterSettings(totalInstances = 3, maxInstancesPerNode = 1))), "router5")

  "A cluster with a ClusterAdaptiveLoadBalancingRouter" must {
    "start cluster with 5 nodes" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      awaitCond(clusterView.members.filter(_.status == MemberStatus.Up).size == roles.size)
      awaitCond(clusterView.clusterMetrics.size == roles.size)
      enterBarrier("cluster-metrics-consumer-ready")
    }
    // TODO the rest of the necessary testing. All the work needed for consumption and extraction
    // of the data needed is in ClusterMetricsCollector._
  }
}
