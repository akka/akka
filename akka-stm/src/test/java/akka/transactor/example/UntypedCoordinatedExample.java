package akka.transactor.example;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.Future;
import akka.testkit.AkkaSpec;
import akka.transactor.Coordinated;

public class UntypedCoordinatedExample {
  public static void main(String[] args) throws InterruptedException {

    ActorSystem application = ActorSystem.create("UntypedCoordinatedExample", AkkaSpec.testConf());

    ActorRef counter1 = application.actorOf(new Props().withCreator(UntypedCoordinatedCounter.class));
    ActorRef counter2 = application.actorOf(new Props().withCreator(UntypedCoordinatedCounter.class));

    counter1.tell(new Coordinated(new Increment(counter2)));

    Thread.sleep(3000);

    long timeout = 5000;

    Future future1 = counter1.ask("GetCount", timeout);
    Future future2 = counter2.ask("GetCount", timeout);

    future1.await();
    if (future1.isCompleted()) {
      if (future1.result().isDefined()) {
        int result = (Integer) future1.result().get();
        System.out.println("counter 1: " + result);
      }
    }

    future2.await();
    if (future2.isCompleted()) {
      if (future2.result().isDefined()) {
        int result = (Integer) future2.result().get();
        System.out.println("counter 2: " + result);
      }
    }

    application.stop(counter1);
    application.stop(counter2);

    application.stop();
  }
}
