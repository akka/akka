package sample.cluster.factorial.japi;

import java.util.concurrent.TimeUnit;

import sample.cluster.transformation.japi.TransformationMessages.TransformationJob;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.OnSuccess;
import akka.util.Timeout;
import static akka.pattern.Patterns.ask;

public class FactorialFrontendMain {

  public static void main(String[] args) throws Exception {
    int upToN = (args.length == 0 ? 200 : Integer.valueOf(args[0]));

    ActorSystem system = ActorSystem.create("ClusterSystem");

    ActorRef frontend = system.actorOf(new Props(
        FactorialFrontend.class), "factorialFrontend");
    
    system.log().info("Starting up");
    // wait to let cluster converge and gather metrics
    Thread.sleep(10000);

    system.log().info("Starting many factorials up to [{}]", upToN);
    for (int i = 0; i < 1000; i++) {
      for (int n = 1; n <= upToN; n++) {
        frontend.tell(n, null);
      }
    }

  }

}
