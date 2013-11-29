package sample.cluster.stats

import akka.actor.Actor
import akka.actor.Props

// not used, only for documentation
abstract class StatsService2 extends Actor {
  //#router-lookup-in-code
  import akka.cluster.routing.ClusterRouterGroup
  import akka.cluster.routing.ClusterRouterGroupSettings
  import akka.routing.ConsistentHashingGroup

  val workerRouter = context.actorOf(
    ClusterRouterGroup(ConsistentHashingGroup(Nil), ClusterRouterGroupSettings(
      totalInstances = 100, routeesPaths = List("/user/statsWorker"),
      allowLocalRoutees = true, useRole = Some("compute"))).props(),
    name = "workerRouter2")
  //#router-lookup-in-code
}

// not used, only for documentation
abstract class StatsService3 extends Actor {
  //#router-deploy-in-code
  import akka.cluster.routing.ClusterRouterPool
  import akka.cluster.routing.ClusterRouterPoolSettings
  import akka.routing.ConsistentHashingPool

  val workerRouter = context.actorOf(
    ClusterRouterPool(ConsistentHashingPool(0), ClusterRouterPoolSettings(
      totalInstances = 100, maxInstancesPerNode = 3,
      allowLocalRoutees = false, useRole = None)).props(Props[StatsWorker]),
    name = "workerRouter3")
  //#router-deploy-in-code
}
