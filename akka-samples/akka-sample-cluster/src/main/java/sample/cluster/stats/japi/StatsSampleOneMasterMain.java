package sample.cluster.stats.japi;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.contrib.pattern.ClusterSingletonManager;

public class StatsSampleOneMasterMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port when specified as program argument
    final Config config =
      (args.length > 0 ?
        ConfigFactory.parseString(String.format("akka.remote.netty.tcp.port=%s", args[0])) :
        ConfigFactory.empty()).
          withFallback(ConfigFactory.parseString("akka.cluster.roles = [compute]")).
          withFallback(ConfigFactory.load());

    ActorSystem system = ActorSystem.create("ClusterSystem", config);

    //#create-singleton-manager
    system.actorOf(ClusterSingletonManager.defaultProps(
      Props.create(StatsService.class),  
      "statsService", PoisonPill.getInstance(), "compute"), 
      "singleton");
    //#create-singleton-manager

    system.actorOf(Props.create(StatsFacade.class), "statsFacade");

  }
}
