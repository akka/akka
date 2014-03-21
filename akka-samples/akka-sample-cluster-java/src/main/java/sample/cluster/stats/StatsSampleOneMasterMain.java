package sample.cluster.stats;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.contrib.pattern.ClusterSingletonManager;
import akka.contrib.pattern.ClusterSingletonProxy;

public class StatsSampleOneMasterMain {

  public static void main(String[] args) {
    if (args.length == 0) {
      startup(new String[] { "2551", "2552", "0" });
      StatsSampleOneMasterClientMain.main(new String[0]);
    } else {
      startup(args);
    }
  }

  public static void startup(String[] ports) {
    for (String port : ports) {
      // Override the configuration of the port
      Config config = ConfigFactory
          .parseString("akka.remote.netty.tcp.port=" + port)
          .withFallback(
              ConfigFactory.parseString("akka.cluster.roles = [compute]"))
          .withFallback(ConfigFactory.load("stats2"));

      ActorSystem system = ActorSystem.create("ClusterSystem", config);

      //#create-singleton-manager
      system.actorOf(ClusterSingletonManager.defaultProps(
          Props.create(StatsService.class), "statsService",
          PoisonPill.getInstance(), "compute"), "singleton");
      //#create-singleton-manager

      //#singleton-proxy
      system.actorOf(ClusterSingletonProxy.defaultProps("/user/singleton/statsService",
        "compute"), "statsServiceProxy");
      //#singleton-proxy
    }

  }
}
