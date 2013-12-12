package sample.cluster.factorial

import akka.actor.Props
import akka.actor.Actor

// not used, only for documentation
abstract class FactorialFrontend2 extends Actor {
  //#router-lookup-in-code
  import akka.cluster.routing.ClusterRouterGroup
  import akka.cluster.routing.ClusterRouterGroupSettings
  import akka.cluster.routing.AdaptiveLoadBalancingGroup
  import akka.cluster.routing.HeapMetricsSelector

  val backend = context.actorOf(
    ClusterRouterGroup(AdaptiveLoadBalancingGroup(HeapMetricsSelector),
      ClusterRouterGroupSettings(
        totalInstances = 100, routeesPaths = List("/user/factorialBackend"),
        allowLocalRoutees = true, useRole = Some("backend"))).props(),
    name = "factorialBackendRouter2")
  //#router-lookup-in-code
}

// not used, only for documentation
abstract class FactorialFrontend3 extends Actor {
  //#router-deploy-in-code
  import akka.cluster.routing.ClusterRouterPool
  import akka.cluster.routing.ClusterRouterPoolSettings
  import akka.cluster.routing.AdaptiveLoadBalancingPool
  import akka.cluster.routing.SystemLoadAverageMetricsSelector

  val backend = context.actorOf(
    ClusterRouterPool(AdaptiveLoadBalancingPool(
      SystemLoadAverageMetricsSelector), ClusterRouterPoolSettings(
      totalInstances = 100, maxInstancesPerNode = 3,
      allowLocalRoutees = false, useRole = Some("backend"))).props(Props[FactorialBackend]),
    name = "factorialBackendRouter3")
  //#router-deploy-in-code
}