package sample.cluster.stats.japi;

import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;
import akka.contrib.pattern.ClusterSingletonManager;
import akka.contrib.pattern.ClusterSingletonPropsFactory;

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
        "  /singleton/statsService/workerRouter {      \n" +
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
    //#start-router-deploy

    //#create-singleton-manager
    system.actorOf(new Props(new UntypedActorFactory() {
      @Override
      public ClusterSingletonManager create() {
        return new ClusterSingletonManager("statsService", PoisonPill.getInstance(),
            new ClusterSingletonPropsFactory() {
              @Override
              public Props create(Object handOverData) {
                return new Props(StatsService.class);
              }
            });
      }
    }), "singleton");
    //#create-singleton-manager

    system.actorOf(new Props(StatsFacade.class), "statsFacade");

  }
}
