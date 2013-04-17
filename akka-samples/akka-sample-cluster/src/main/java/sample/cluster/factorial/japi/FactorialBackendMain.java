package sample.cluster.factorial.japi;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;
import akka.actor.Props;

public class FactorialBackendMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port when specified as program argument
    final Config config =
      (args.length > 0 ?
        ConfigFactory.parseString(String.format("akka.remote.netty.tcp.port=%s", args[0])) :
        ConfigFactory.empty()).
          withFallback(ConfigFactory.parseString("akka.cluster.roles = [backend]")).
          withFallback(ConfigFactory.load("factorial"));

    ActorSystem system = ActorSystem.create("ClusterSystem", config);

    system.actorOf(Props.create(FactorialBackend.class), "factorialBackend");

    system.actorOf(Props.create(MetricsListener.class), "metricsListener");

  }

}
