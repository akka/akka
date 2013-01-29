package sample.cluster.factorial.japi;

import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.cluster.Cluster;

public class FactorialFrontendMain {

  public static void main(String[] args) throws Exception {
    final int upToN = (args.length == 0 ? 200 : Integer.valueOf(args[0]));

    final ActorSystem system = ActorSystem.create("ClusterSystem", ConfigFactory.load("factorial"));
    system.log().info("Factorials will start when 3 members in the cluster.");
    //#registerOnUp
    Cluster.get(system).registerOnMemberUp(new Runnable() {
      @Override
      public void run() {
        system.actorOf(new Props(new UntypedActorFactory() {
          @Override
          public UntypedActor create() {
            return new FactorialFrontend(upToN, true);
          }
        }), "factorialFrontend");
      }
    });
    //#registerOnUp
  }

}
