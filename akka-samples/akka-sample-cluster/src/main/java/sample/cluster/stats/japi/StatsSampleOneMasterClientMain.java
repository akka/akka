package sample.cluster.stats.japi;

import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.contrib.pattern.ClusterSingletonManager;
import akka.contrib.pattern.ClusterSingletonPropsFactory;

public class StatsSampleOneMasterClientMain {

  public static void main(String[] args) throws Exception {
    ActorSystem system = ActorSystem.create("ClusterSystem");

    // the client is also part of the cluster
    system.actorOf(new Props(new UntypedActorFactory() {
      @Override
      public ClusterSingletonManager create() {
        return new ClusterSingletonManager("statsService", PoisonPill.getInstance(),
            new ClusterSingletonPropsFactory() {
              @Override
              public Props create(Object handOverData) {
                return new Props(StatsService.class);
              }
            });
      }
    }), "singleton");

    system.actorOf(new Props(new UntypedActorFactory() {
      @Override
      public UntypedActor create() {
        return new StatsSampleClient("/user/statsFacade");
      }
    }), "client");

  }

}
