package sample.cluster.stats.japi;

import akka.actor.ActorSystem;
import akka.actor.Props;

import com.typesafe.config.ConfigFactory;

public class StatsSampleOneMasterMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port
    // when specified as program argument
    if (args.length > 0)
      System.setProperty("akka.remote.netty.port", args[0]);

    //#start-router-deploy
    ActorSystem system = ActorSystem.create("ClusterSystem",
      ConfigFactory.parseString(
        "akka.actor.deployment {                       \n" +
        "  /statsFacade/statsService/workerRouter {    \n" +
        "    router = consistent-hashing               \n" +
        "    nr-of-instances = 100                     \n" +
        "    cluster {                                 \n" +
        "      enabled = on                            \n" +
        "      max-nr-of-instances-per-node = 3        \n" +
        "      allow-local-routees = off               \n" +
        "    }                                         \n" +
        "  }                                           \n" +
        "}                                             \n")
        .withFallback(ConfigFactory.load()));

    system.actorOf(new Props(StatsFacade.class), "statsFacade");
    //#start-router-deploy

  }

}
