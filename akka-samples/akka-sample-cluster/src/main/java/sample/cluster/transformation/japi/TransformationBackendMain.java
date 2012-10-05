package sample.cluster.transformation.japi;

import akka.actor.ActorSystem;
import akka.actor.Props;

public class TransformationBackendMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port
    // when specified as program argument
    if (args.length > 0)
      System.setProperty("akka.remote.netty.port", args[0]);

    ActorSystem system = ActorSystem.create("ClusterSystem");

    system.actorOf(new Props(TransformationBackend.class), "backend");

  }

}
