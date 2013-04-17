package sample.cluster.factorial.japi;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.Cluster;

public class FactorialFrontendMain {

  public static void main(String[] args) throws Exception {
    final int upToN = (args.length == 0 ? 200 : Integer.valueOf(args[0]));

    final Config config = ConfigFactory.parseString("akka.cluster.roles = [frontend]").
      withFallback(ConfigFactory.load("factorial"));

    final ActorSystem system = ActorSystem.create("ClusterSystem", config);
    system.log().info("Factorials will start when 2 backend members in the cluster.");
    //#registerOnUp
    Cluster.get(system).registerOnMemberUp(new Runnable() {
      @Override
      public void run() {
        system.actorOf(Props.create(FactorialFrontend.class, upToN, true), "factorialFrontend");
      }
    });
    //#registerOnUp
  }

}
