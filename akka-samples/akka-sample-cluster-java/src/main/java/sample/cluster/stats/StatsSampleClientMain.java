package sample.cluster.stats;

import akka.actor.ActorSystem;
import akka.actor.Props;

import com.typesafe.config.ConfigFactory;

public class StatsSampleClientMain {

  public static void main(String[] args) {
    // note that client is not a compute node, role not defined
    ActorSystem system = ActorSystem.create("ClusterSystem",
        ConfigFactory.load("stats1"));
    system.actorOf(Props.create(StatsSampleClient.class, "/user/statsService"),
        "client");
  }
}
