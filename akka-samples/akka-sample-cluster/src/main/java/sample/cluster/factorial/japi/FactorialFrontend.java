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

  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  ActorRef backend = getContext().actorOf(
    new Props(FactorialBackend.class).withRouter(FromConfig.getInstance()),
    "factorialBackendRouter");

  @Override
  public void onReceive(Object message) {
    if (message instanceof Integer) {
      Integer n = (Integer) message;
      backend.tell(n, getSelf());

    } else if (message instanceof FactorialResult) {
      FactorialResult result = (FactorialResult) message;
      log.info("{}! = {}", result.n, result.factorial);

    } else {
      unhandled(message);
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