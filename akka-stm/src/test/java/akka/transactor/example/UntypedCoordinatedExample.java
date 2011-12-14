package akka.transactor.example;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.testkit.AkkaSpec;
import akka.transactor.Coordinated;

import akka.util.Duration;
import java.util.concurrent.TimeUnit;

public class UntypedCoordinatedExample {
  public static void main(String[] args) throws InterruptedException {

    ActorSystem app = ActorSystem.create("UntypedCoordinatedExample", AkkaSpec.testConf());

    ActorRef counter1 = app.actorOf(new Props().withCreator(UntypedCoordinatedCounter.class));
    ActorRef counter2 = app.actorOf(new Props().withCreator(UntypedCoordinatedCounter.class));

    counter1.tell(new Coordinated(new Increment(counter2)));

    Thread.sleep(3000);

    long timeout = 5000;
    Duration d = Duration.create(timeout, TimeUnit.MILLISECONDS);

    Future<Object> future1 = counter1.ask("GetCount", timeout);
    Future<Object> future2 = counter2.ask("GetCount", timeout);

    int count1 = (Integer) Await.result(future1, d);
    System.out.println("counter 1: " + count1);
    int count2 = (Integer) Await.result(future2, d);
    System.out.println("counter 1: " + count2);

    app.shutdown();
  }
}
