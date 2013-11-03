package sample.cluster.factorial.japi;

import java.util.Collections;

import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.routing.FromConfig;
import akka.cluster.routing.AdaptiveLoadBalancingPool;
import akka.cluster.routing.AdaptiveLoadBalancingGroup;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterGroup;
import akka.cluster.routing.ClusterRouterGroupSettings;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.cluster.routing.HeapMetricsSelector;
import akka.cluster.routing.SystemLoadAverageMetricsSelector;

//#frontend
public class FactorialFrontend extends UntypedActor {
  final int upToN;
  final boolean repeat;

  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  ActorRef backend = getContext().actorOf(
      FromConfig.getInstance().props(),
    "factorialBackendRouter");

  public FactorialFrontend(int upToN, boolean repeat) {
    this.upToN = upToN;
    this.repeat = repeat;
  }

  @Override
  public void preStart() {
    sendJobs();
  }

  @Override
  public void onReceive(Object message) {
    if (message instanceof FactorialResult) {
      FactorialResult result = (FactorialResult) message;
      if (result.n == upToN) {
        log.debug("{}! = {}", result.n, result.factorial);
        if (repeat) sendJobs();
      }

    } else {
      unhandled(message);
    }
  }

  void sendJobs() {
    log.info("Starting batch of factorials up to [{}]", upToN);
    for (int n = 1; n <= upToN; n++) {
      backend.tell(n, getSelf());
    }
  }

}
//#frontend


//not used, only for documentation
abstract class FactorialFrontend2 extends UntypedActor {
  //#router-lookup-in-code
  int totalInstances = 100;
  String routeesPath = "/user/factorialBackend";
  boolean allowLocalRoutees = true;
  String useRole = "backend";
  ActorRef backend = getContext().actorOf(
    new ClusterRouterGroup(
      new AdaptiveLoadBalancingGroup(HeapMetricsSelector.getInstance(), Collections.<String>emptyList()),
      new ClusterRouterGroupSettings(
        totalInstances, routeesPath, allowLocalRoutees, useRole)).props(),
      "factorialBackendRouter2");
  //#router-lookup-in-code
}

//not used, only for documentation
abstract class FactorialFrontend3 extends UntypedActor {
  //#router-deploy-in-code
  int totalInstances = 100;
  int maxInstancesPerNode = 3;
  boolean allowLocalRoutees = false;
  String useRole = "backend";
  ActorRef backend = getContext().actorOf(
    new ClusterRouterPool(
      new AdaptiveLoadBalancingPool(
        SystemLoadAverageMetricsSelector.getInstance(), 0),
      new ClusterRouterPoolSettings(
        totalInstances, maxInstancesPerNode, allowLocalRoutees, useRole)).
        props(Props.create(FactorialBackend.class)),
      "factorialBackendRouter3");
  //#router-deploy-in-code
}