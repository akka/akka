package sample.cluster.simple.japi;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.ClusterDomainEvent;

public class SimpleClusterApp {

  public static void main(String[] args) {
    // Override the configuration of the port
    // when specified as program argument
    if (args.length > 0)
      System.setProperty("akka.remote.netty.port", args[0]);

    // Create an Akka system
    ActorSystem system = ActorSystem.create("ClusterSystem");

    // Create an actor that handles cluster domain events
    ActorRef clusterListener = system.actorOf(new Props(
        SimpleClusterListener.class), "clusterListener");

    // Add subscription of cluster events
    Cluster.get(system).subscribe(clusterListener,
        ClusterDomainEvent.class);
  }
}
