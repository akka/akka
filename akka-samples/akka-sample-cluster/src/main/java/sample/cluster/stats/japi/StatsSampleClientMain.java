package sample.cluster.stats.japi;

import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;

public class StatsSampleClientMain {

  public static void main(String[] args) throws Exception {
    // note that client is not a compute node, role not defined
    ActorSystem system = ActorSystem.create("ClusterSystem");
    system.actorOf(new Props(new UntypedActorFactory() {
      @Override
      public UntypedActor create() {
        return new StatsSampleClient("/user/statsService");
      }
    }), "client");

  }
}
