package sample.cluster.factorial.japi;

import akka.actor.ActorSystem;
import akka.actor.Props;

public class FactorialBackendMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port
    // when specified as program argument
    if (args.length > 0)
      System.setProperty("akka.remote.netty.port", args[0]);

    ActorSystem system = ActorSystem.create("ClusterSystem");

    system.actorOf(new Props(FactorialBackend.class), "factorialBackend");

    system.actorOf(new Props(MetricsListener.class), "metricsListener");

  }

}
