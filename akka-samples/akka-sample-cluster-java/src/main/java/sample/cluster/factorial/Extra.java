package sample.cluster.factorial;

import java.util.Arrays;
import java.util.Collections;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.cluster.metrics.AdaptiveLoadBalancingGroup;
import akka.cluster.metrics.AdaptiveLoadBalancingPool;
import akka.cluster.routing.ClusterRouterGroup;
import akka.cluster.routing.ClusterRouterGroupSettings;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.cluster.metrics.HeapMetricsSelector;
import akka.cluster.metrics.SystemLoadAverageMetricsSelector;

//not used, only for documentation
abstract class FactorialFrontend2 extends AbstractActor {
  //#router-lookup-in-code
  int totalInstances = 100;
  Iterable<String> routeesPaths = Arrays.asList("/user/factorialBackend", "");
  boolean allowLocalRoutees = true;
  String useRole = "backend";
  ActorRef backend = getContext().actorOf(
      new ClusterRouterGroup(new AdaptiveLoadBalancingGroup(
          HeapMetricsSelector.getInstance(), Collections.<String> emptyList()),
          new ClusterRouterGroupSettings(totalInstances, routeesPaths,
              allowLocalRoutees, useRole)).props(), "factorialBackendRouter2");
  //#router-lookup-in-code
}

//not used, only for documentation
abstract class FactorialFrontend3 extends AbstractActor {
  //#router-deploy-in-code
  int totalInstances = 100;
  int maxInstancesPerNode = 3;
  boolean allowLocalRoutees = false;
  String useRole = "backend";
  ActorRef backend = getContext().actorOf(
      new ClusterRouterPool(new AdaptiveLoadBalancingPool(
          SystemLoadAverageMetricsSelector.getInstance(), 0),
          new ClusterRouterPoolSettings(totalInstances, maxInstancesPerNode,
              allowLocalRoutees, useRole)).props(Props
          .create(FactorialBackend.class)), "factorialBackendRouter3");
  //#router-deploy-in-code
}
