package sample.cluster.factorial.japi;

import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.routing.FromConfig;
import akka.cluster.routing.AdaptiveLoadBalancingRouter;
import akka.cluster.routing.ClusterRouterConfig;
import akka.cluster.routing.ClusterRouterSettings;
import akka.cluster.routing.HeapMetricsSelector;
import akka.cluster.routing.SystemLoadAverageMetricsSelector;

//#frontend
public class FactorialFrontend extends UntypedActor {
  final int upToN;
  final boolean repeat;

  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  ActorRef backend = getContext().actorOf(
    new Props(FactorialBackend.class).withRouter(FromConfig.getInstance()),
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
  String routeesPath = "/user/statsWorker";
  boolean allowLocalRoutees = true;
  ActorRef backend = getContext().actorOf(
    new Props(FactorialBackend.class).withRouter(new ClusterRouterConfig(
      new AdaptiveLoadBalancingRouter(HeapMetricsSelector.getInstance(), 0), 
      new ClusterRouterSettings(
        totalInstances, routeesPath, allowLocalRoutees))),
      "factorialBackendRouter2");
  //#router-lookup-in-code
}

//not used, only for documentation
abstract class StatsService3 extends UntypedActor {
  //#router-deploy-in-code
  int totalInstances = 100;
  int maxInstancesPerNode = 3;
  boolean allowLocalRoutees = false;
  ActorRef backend = getContext().actorOf(
    new Props(FactorialBackend.class).withRouter(new ClusterRouterConfig(
      new AdaptiveLoadBalancingRouter(
        SystemLoadAverageMetricsSelector.getInstance(), 0), 
      new ClusterRouterSettings(
        totalInstances, maxInstancesPerNode, allowLocalRoutees))),
      "factorialBackendRouter3");
  //#router-deploy-in-code
}