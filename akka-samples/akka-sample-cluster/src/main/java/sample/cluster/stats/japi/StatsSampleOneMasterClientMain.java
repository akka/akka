package sample.cluster.stats.japi;

import akka.actor.ActorSystem;
import akka.actor.Props;

public class StatsSampleOneMasterClientMain {

  public static void main(String[] args) throws Exception {
    // note that client is not a compute node, role not defined
    ActorSystem system = ActorSystem.create("ClusterSystem");
    system.actorOf(Props.create(StatsSampleClient.class, "/user/statsFacade"),
      "client");

  }

}
