package akka.transactor.example;

import akka.transactor.Coordinated;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.dispatch.Future;
import akka.dispatch.Futures;

public class UntypedCoordinatedExample {
    public static void main(String[] args) throws InterruptedException {
        System.out.println();
        System.out.println("Untyped transactor example");
        System.out.println();

        ActorRef counter1 = UntypedActor.actorOf(UntypedCoordinatedCounter.class).start();
        ActorRef counter2 = UntypedActor.actorOf(UntypedCoordinatedCounter.class).start();

        counter1.sendOneWay(new Coordinated(new Increment(counter2)));

        Thread.sleep(3000);

        Future future1 = counter1.sendRequestReplyFuture("GetCount");
        Future future2 = counter2.sendRequestReplyFuture("GetCount");

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

        counter1.stop();
        counter2.stop();
    }
}
