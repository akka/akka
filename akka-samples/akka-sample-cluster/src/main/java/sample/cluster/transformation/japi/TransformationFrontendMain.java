package sample.cluster.transformation.japi;

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

public class TransformationFrontendMain {

  public static void main(String[] args) throws Exception {
    // Override the configuration of the port
    // when specified as program argument
    if (args.length > 0)
      System.setProperty("akka.remote.netty.port", args[0]);

    ActorSystem system = ActorSystem.create("ClusterSystem");

    ActorRef frontend = system.actorOf(new Props(
        TransformationFrontend.class), "frontend");
    Timeout timeout = new Timeout(Duration.create(5, TimeUnit.SECONDS));
    final ExecutionContext ec = system.dispatcher();
    for (int n = 1; n <= 120; n++) {
      ask(frontend, new TransformationJob("hello-" + n), timeout)
          .onSuccess(new OnSuccess<Object>() {
            public void onSuccess(Object result) {
              System.out.println(result);
            }
          }, ec);

      // wait a while until next request,
      // to avoid flooding the console with output
      Thread.sleep(2000);
    }
    system.shutdown();

  }

}
