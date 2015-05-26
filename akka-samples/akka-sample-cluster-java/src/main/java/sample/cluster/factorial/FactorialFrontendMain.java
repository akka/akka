package sample.cluster.factorial;

import java.util.concurrent.TimeUnit;
import scala.concurrent.duration.FiniteDuration;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.Cluster;

public class FactorialFrontendMain {

  public static void main(String[] args) {
    final int upToN = 200;

    final Config config = ConfigFactory.parseString(
        "akka.cluster.roles = [frontend]").withFallback(
        ConfigFactory.load("factorial"));

    final ActorSystem system = ActorSystem.create("ClusterSystem", config);
    system.log().info(
            "Factorials will start when 2 backend members in the cluster.");
    //#registerOnUp
    Cluster.get(system).registerOnMemberUp(new Runnable() {
      @Override
      public void run() {
        system.actorOf(Props.create(FactorialFrontend.class, upToN, true),
            "factorialFrontend");
      }
    });
    //#registerOnUp

    //#registerOnRemoved
    Cluster.get(system).registerOnMemberRemoved(new Runnable() {
      @Override
      public void run() {
        // exit JVM when ActorSystem has been terminated
        final Runnable exit = new Runnable() {
          @Override
          public void run() {
            System.exit(-1);
          }
        };
        system.registerOnTermination(exit);
        // in case ActorSystem shutdown takes longer than 10 seconds, 
        // exit the JVM forcefully anyway
        system.scheduler().scheduleOnce(FiniteDuration.create(10, TimeUnit.SECONDS), 
            exit, system.dispatcher());
        // shut down ActorSystem
        system.shutdown();
      }
    });
    //#registerOnRemoved

  }

}
