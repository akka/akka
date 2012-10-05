package sample.cluster.stats.japi;

import akka.actor.ActorSystem;
import akka.actor.Props;

import com.typesafe.config.ConfigFactory;

public class StatsSampleMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port
    // when specified as program argument
    if (args.length > 0)
      System.setProperty("akka.remote.netty.port", args[0]);

    //#start-router-lookup
    ActorSystem system = ActorSystem.create("ClusterSystem",
      ConfigFactory.parseString(
        "akka.actor.deployment {                        \n" +
        "  /statsService/workerRouter {                 \n" +
        "    router = consistent-hashing                \n" +
        "    nr-of-instances = 100                      \n" +
        "    cluster {                                  \n" +
        "      enabled = on                             \n" +
        "      routees-path = \"/user/statsWorker\"     \n" +
        "      allow-local-routees = on                 \n" +
        "    }                                          \n" +
        "  }                                            \n" +
        "}                                              \n")
        .withFallback(ConfigFactory.load()));

    system.actorOf(new Props(StatsWorker.class), "statsWorker");
    system.actorOf(new Props(StatsService.class), "statsService");
    //#start-router-lookup

  }
}
