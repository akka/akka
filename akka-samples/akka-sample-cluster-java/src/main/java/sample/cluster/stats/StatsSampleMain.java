package sample.cluster.stats;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.ActorSystem;
import akka.actor.Props;

public class StatsSampleMain {

  public static void main(String[] args) {
    if (args.length == 0) {
      startup(new String[] { "2551", "2552", "0" });
      StatsSampleClientMain.main(new String[0]);
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
          .withFallback(ConfigFactory.load("stats1"));

      ActorSystem system = ActorSystem.create("ClusterSystem", config);

      system.actorOf(Props.create(StatsWorker.class), "statsWorker");
      system.actorOf(Props.create(StatsService.class), "statsService");
    }

  }
}
