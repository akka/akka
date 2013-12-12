package sample.cluster.stats;

import java.util.Collections;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.routing.ClusterRouterGroup;
import akka.cluster.routing.ClusterRouterGroupSettings;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.routing.ConsistentHashingGroup;
import akka.routing.ConsistentHashingPool;

//not used, only for documentation
abstract class StatsService2 extends UntypedActor {
  //#router-lookup-in-code
  int totalInstances = 100;
  Iterable<String> routeesPaths = Collections
      .singletonList("/user/statsWorker");
  boolean allowLocalRoutees = true;
  String useRole = "compute";
  ActorRef workerRouter = getContext().actorOf(
      new ClusterRouterGroup(new ConsistentHashingGroup(routeesPaths),
          new ClusterRouterGroupSettings(totalInstances, routeesPaths,
              allowLocalRoutees, useRole)).props(), "workerRouter2");
  //#router-lookup-in-code
}

//not used, only for documentation
abstract class StatsService3 extends UntypedActor {
  //#router-deploy-in-code
  int totalInstances = 100;
  int maxInstancesPerNode = 3;
  boolean allowLocalRoutees = false;
  String useRole = "compute";
  ActorRef workerRouter = getContext().actorOf(
      new ClusterRouterPool(new ConsistentHashingPool(0),
          new ClusterRouterPoolSettings(totalInstances, maxInstancesPerNode,
              allowLocalRoutees, useRole)).props(Props
          .create(StatsWorker.class)), "workerRouter3");
  //#router-deploy-in-code
}
