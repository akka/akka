package sample.cluster.stats.japi;

import sample.cluster.stats.japi.StatsMessages.StatsJob;
//#imports
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.cluster.routing.ClusterRouterConfig;
import akka.cluster.routing.ClusterRouterSettings;
import akka.routing.ConsistentHashingRouter;
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope;
import akka.routing.FromConfig;
//#imports

//#service
public class StatsService extends UntypedActor {

  ActorRef workerRouter = getContext().actorOf(
      new Props(StatsWorker.class).withRouter(FromConfig.getInstance()),
      "workerRouter");

  @Override
  public void onReceive(Object message) {
    if (message instanceof StatsJob) {
      StatsJob job = (StatsJob) message;
      if (job.getText().equals("")) {
        unhandled(message);
      } else {
        final String[] words = job.getText().split(" ");
        final ActorRef replyTo = getSender();

        // create actor that collects replies from workers
        ActorRef aggregator = getContext().actorOf(
            new Props(new UntypedActorFactory() {
              @Override
              public UntypedActor create() {
                return new StatsAggregator(words.length, replyTo);
              }
            }));

        // send each word to a worker
        for (String word : words) {
          workerRouter.tell(new ConsistentHashableEnvelope(word, word),
              aggregator);
        }
      }

    } else {
      unhandled(message);
    }
  }
}

//#service

//not used, only for documentation
abstract class StatsService2 extends UntypedActor {
  //#router-lookup-in-code
  int totalInstances = 100;
  String routeesPath = "/user/statsWorker";
  boolean allowLocalRoutees = true;
  ActorRef workerRouter = getContext().actorOf(
      new Props(StatsWorker.class).withRouter(new ClusterRouterConfig(
          new ConsistentHashingRouter(0), new ClusterRouterSettings(
              totalInstances, routeesPath, allowLocalRoutees))),
      "workerRouter2");
  //#router-lookup-in-code
}

//not used, only for documentation
abstract class StatsService3 extends UntypedActor {
  //#router-deploy-in-code
  int totalInstances = 100;
  int maxInstancesPerNode = 3;
  boolean allowLocalRoutees = false;
  ActorRef workerRouter = getContext().actorOf(
      new Props(StatsWorker.class).withRouter(new ClusterRouterConfig(
          new ConsistentHashingRouter(0), new ClusterRouterSettings(
              totalInstances, maxInstancesPerNode, allowLocalRoutees))),
      "workerRouter3");
  //#router-deploy-in-code
}
